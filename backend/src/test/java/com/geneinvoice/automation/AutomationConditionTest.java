package com.geneinvoice.automation;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.ConditionNode;
import com.geneinvoice.common.query.Conditions;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AND/OR condition tree (PRD A2), tested through the REAL executor rather than against a
 * hand-rolled matcher, because the whole claim of this unit is that a rule's conditions are the
 * filter bar's own machinery rearranged: the same {@code FilterSpec} leaves, the same
 * {@code ColumnDef}s, the same {@code FilterPredicates} builder, the same 400 texts, and the same
 * mandatory region predicate underneath. Nothing in this unit is wired to a rule, an endpoint or a
 * table yet — A-RULES calls validate/factory and A-CONSUMER calls factory (A2).
 */
class AutomationConditionTest extends IntegrationTestBase {

    static final TableSchema INVOICES = TableSchemas.INVOICES;
    static final TableSchema CUSTOMERS = TableSchemas.CUSTOMERS;

    static final Instant RAISED = Instant.parse("2026-01-05T09:00:00Z");

    @Autowired TableQueryExecutor queryExecutor;
    @Autowired ConditionJson conditionJson;

    User admin;
    User cashier;
    User collector;
    Region north;
    Customer homeAccount;
    Customer awayAccount;

    /** balance 100000, not overdue, UNPAID. */
    Invoice big;
    /** balance 1000, not overdue, UNPAID. */
    Invoice small;
    /** balance 500, OVERDUE, UNPAID. */
    Invoice late;
    /** balance 80000, OVERDUE, PARTIALLY_PAID — the only row that is both big and late. */
    Invoice lateBig;
    /** balance 0, FULLY_PAID, past due date but settled so not overdue. */
    Invoice paidOff;
    /** balance 70000, not overdue, UNPAID — and in the OTHER branch. */
    Invoice away;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cashier = userRepository.findByUsername("cashier").orElseThrow();
        collector = user("carl.collector", DataSeeder.ROLE_COLLECTION_POC);
        north = region("NORTH");
        homeAccount = customer("Home Ltd");
        awayAccount = customerRepository.save(
                Customer.builder().name("Away Ltd").region(north).build());

        LocalDate past = LocalDate.now().minusDays(30);
        LocalDate future = LocalDate.now().plusDays(30);
        big = invoice(homeAccount, "AC-BIG", "100000.00", "0.00", InvoiceStatus.UNPAID, future);
        small = invoice(homeAccount, "AC-SMALL", "1000.00", "0.00", InvoiceStatus.UNPAID, future);
        late = invoice(homeAccount, "AC-LATE", "500.00", "0.00", InvoiceStatus.UNPAID, past);
        lateBig = invoice(homeAccount, "AC-LATEBIG", "90000.00", "10000.00",
                InvoiceStatus.PARTIALLY_PAID, past);
        paidOff = invoice(homeAccount, "AC-PAID", "90000.00", "90000.00",
                InvoiceStatus.FULLY_PAID, past);
        away = invoice(awayAccount, "AC-AWAY", "70000.00", "0.00", InvoiceStatus.UNPAID, future);
    }

    // ---------------------------------------------------------------- A2: the tree

    @Test
    void anOrOfTwoConditionsFindsTheRecordsThatMatchEitherOne() {
        actAs(admin);

        // Either big OR late. `late` is tiny and `big` is punctual, so a tree that quietly
        // AND-ed its children would return neither of them.
        assertThat(matching(INVOICES, Invoice.class,
                or(leaf("balance:gt:50000"), leaf("overdue:eq:true"))))
                .containsExactlyInAnyOrder(big.getId(), lateBig.getId(), late.getId(), away.getId());

        // The same two leaves joined the other way is a strictly smaller set, which is what makes
        // the assertion above about the connector and not about the fixtures.
        assertThat(matching(INVOICES, Invoice.class,
                and(leaf("balance:gt:50000"), leaf("overdue:eq:true"))))
                .containsExactly(lateBig.getId());
    }

    @Test
    void anAndNestedInsideAnOrIsEvaluatedTheWayItWasWritten() {
        actAs(admin);

        // (UNPAID AND big) OR overdue — the AND arm skips lateBig (PARTIALLY_PAID) and the OR arm
        // puts it back, which no flattening of the three leaves can reproduce.
        assertThat(matching(INVOICES, Invoice.class,
                or(and(leaf("status:eq:UNPAID"), leaf("balance:gt:50000")), leaf("overdue:eq:true"))))
                .containsExactlyInAnyOrder(big.getId(), away.getId(), late.getId(), lateBig.getId());

        // The SAME three leaves regrouped: UNPAID AND (big OR overdue). lateBig is gone because
        // it is not UNPAID. Same leaves, different shape, different answer — so the shape is what
        // is being obeyed.
        assertThat(matching(INVOICES, Invoice.class,
                and(leaf("status:eq:UNPAID"), or(leaf("balance:gt:50000"), leaf("overdue:eq:true")))))
                .containsExactlyInAnyOrder(big.getId(), away.getId(), late.getId());
    }

    // ---------------------------------------------------------------- A2: refusals, in existing words

    @Test
    void aConditionNamingAColumnTheTableDoesNotOfferIsRefusedInTheFilterBarsOwnWords() {
        assertThatThrownBy(() -> Conditions.validate(
                or(leaf("balance:gt:1"), leaf("secretColumn:eq:1")), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown column: secretColumn");

        // Byte for byte the text the filter bar's own path produces for the same mistake: there is
        // one vocabulary for "that column does not exist", not two (A2).
        assertThatThrownBy(() -> TableQuery.parseUnpaged(INVOICES, null, List.of("secretColumn:eq:1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown column: secretColumn");

        // A column that exists on ANOTHER table is just as unknown here: the schema the rule names
        // is the schema the leaf is checked against.
        assertThatThrownBy(() -> Conditions.validate(leaf("creditBalance:gt:1"), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown column: creditBalance");
        assertThatCode(() -> Conditions.validate(leaf("creditBalance:gt:1"), CUSTOMERS))
                .doesNotThrowAnyException();

        // A column that exists but is sort-only. No schema a rule can name today has one, but
        // DocumentService and EmailService both build private schemas exactly like this, so the
        // branch is real and validate must reach it rather than re-implement the check (A2).
        TableSchema sortOnly = TableSchema.of("invoices", Invoice.class, "id,desc",
                ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
                ColumnDef.of("notes", "Notes", ColumnType.TEXT).notFilterable().build());
        assertThatThrownBy(() -> Conditions.validate(leaf("notes:contains:late"), sortOnly))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Column is not filterable: notes");
    }

    @Test
    void aConditionUsingAnOperatorTheColumnTypeRefusesIsRefusedBeforeItCanEverRun() {
        assertThatThrownBy(() -> Conditions.validate(leaf("balance:contains:oops"), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Operator contains is not valid for column balance (MONEY)");

        // And if a rule somehow reached the executor without being validated — an older row, a
        // schema that changed under it — the compile step says the same thing rather than building
        // a predicate the column cannot answer (A2).
        actAs(admin);
        assertThatThrownBy(() -> run(INVOICES, Invoice.class, leaf("balance:contains:oops")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Operator contains is not valid for column balance (MONEY)");

        // An operator that is not in the vocabulary at all is FilterSpec's own 400, raised while
        // the leaf is still a string.
        assertThatThrownBy(() -> leaf("balance:approximately:1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown filter operator: approximately");
    }

    @Test
    void aTreeNestedTooDeepOrHoldingTooManyConditionsIsRefused() {
        assertThatCode(() -> Conditions.validate(nest(Conditions.MAX_DEPTH), INVOICES))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Conditions.validate(nest(Conditions.MAX_DEPTH + 1), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Conditions may not be nested more than 5 deep");

        assertThatCode(() -> Conditions.validate(wide(Conditions.MAX_LEAVES), INVOICES))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Conditions.validate(wide(Conditions.MAX_LEAVES + 1), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A rule may not have more than 40 conditions");

        // The leaves are counted across the WHOLE tree, not per group, or forty groups of forty
        // would be a rule with sixteen hundred conditions in it.
        ConditionNode fat = new ConditionNode.Group(ConditionNode.Connector.OR,
                List.of(wide(21), wide(21)));
        assertThatThrownBy(() -> Conditions.validate(fat, INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A rule may not have more than 40 conditions");

        // The parser caps its own recursion one level looser than the business limit, so hostile
        // input is a 400 rather than a StackOverflowError on the request thread (A2).
        assertThatThrownBy(() -> conditionJson.parse(deepJson(200)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("nested more than 5 deep");
    }

    @Test
    void anEmptyTopLevelGroupMatchesEveryRecordAndAnEmptyNestedGroupIsRefused() {
        actAs(admin);
        ConditionNode everything = and();

        assertThatCode(() -> Conditions.validate(everything, INVOICES)).doesNotThrowAnyException();
        assertThat(matching(INVOICES, Invoice.class, everything)).containsExactlyInAnyOrder(
                big.getId(), small.getId(), late.getId(), lateBig.getId(),
                paidOff.getId(), away.getId());

        // ... and it is EXACTLY the no-conditions answer, which is the property that lets a rule
        // with no conditions and a rule with an empty group be the same rule.
        assertThat(matching(INVOICES, Invoice.class, everything))
                .isEqualTo(matching(INVOICES, Invoice.class, null));

        // A null tree contributes no factory at all. The caller must add the result only when it
        // is non-null: TableQueryExecutor.predicates skips a factory that RETURNS null, but it
        // calls build() on every element of the scope list (A2).
        assertThat(Conditions.factory(null, INVOICES)).isNull();

        // Nested, though, "or anything" would silently turn the whole rule into every record.
        assertThatThrownBy(() -> Conditions.validate(or(leaf("balance:gt:1"), and()), INVOICES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A group needs at least one condition");
    }

    // ---------------------------------------------------------------- A2: reuse, not a parallel engine

    @Test
    void aCustomSubqueryFilterKeepsWorkingInsideAnOr() {
        // collectionPocUserId is a custom PredicateResolver that builds its own EXISTS subquery
        // over customer_pocs; outstanding is a correlated subquery in the column's PATH. If the
        // tree flattened either of them into the outer query they would collide inside an OR.
        customerPocRepository.save(CustomerPoc.builder()
                .customer(homeAccount).user(collector).pocType(PocType.COLLECTION)
                .primary(true).createdByUserId(admin.getId()).build());
        awayAccount.setCreditBalance(new BigDecimal("500.00"));
        customerRepository.save(awayAccount);

        actAs(admin);
        assertThat(matching(CUSTOMERS, Customer.class,
                or(leaf("collectionPocUserId:eq:" + collector.getId()), leaf("creditBalance:gt:100"))))
                .containsExactlyInAnyOrder(homeAccount.getId(), awayAccount.getId());

        // The AND of the same two leaves is empty, so neither arm leaked into the other's subquery
        // and turned the OR into a tautology.
        assertThat(matching(CUSTOMERS, Customer.class,
                and(leaf("collectionPocUserId:eq:" + collector.getId()), leaf("creditBalance:gt:100"))))
                .isEmpty();

        // Two subquery-bearing columns in one OR: an EXISTS filter on one side, a correlated SUM
        // in a path on the other. Home owes 181,500; Away owes 70,000 and holds the credit.
        assertThat(matching(CUSTOMERS, Customer.class,
                or(leaf("outstanding:gt:150000"), leaf("creditBalance:gt:100"))))
                .containsExactlyInAnyOrder(homeAccount.getId(), awayAccount.getId());
        assertThat(matching(CUSTOMERS, Customer.class, leaf("outstanding:gt:150000")))
                .containsExactly(homeAccount.getId());
    }

    @Test
    void aTreeSurvivesTheJsonRoundTripAndItsLeavesStayByteIdenticalToAFilterBarChip() {
        String wire = "{\"op\":\"AND\",\"of\":["
                + "{\"filter\":\"status:in:UNPAID,PARTIALLY_PAID\"},"
                + "{\"op\":\"OR\",\"of\":["
                + "{\"filter\":\"overdue:eq:true\"},"
                + "{\"filter\":\"balance:gt:50000\"}]}]}";

        ConditionNode tree = conditionJson.parse(wire);
        assertThat(conditionJson.write(tree)).isEqualTo(wire);
        assertThat(conditionJson.parse(conditionJson.write(tree))).isEqualTo(tree);

        // Each leaf is the EXACT string a filter chip carries — it goes straight back through the
        // executor's own parse and comes out the same, so a saved rule and a typed filter are one
        // grammar and not two (A2).
        List<String> chips = List.of("status:in:UNPAID,PARTIALLY_PAID", "overdue:eq:true",
                "balance:gt:50000");
        assertThat(leaves(tree)).isEqualTo(chips);
        for (String chip : chips) {
            assertThat(TableQuery.parseUnpaged(INVOICES, null, List.of(chip))
                    .filters().get(0).wire()).isEqualTo(chip);
        }

        // And the tree that JSON describes really does select what it says it does.
        actAs(admin);
        assertThatCode(() -> Conditions.validate(tree, INVOICES)).doesNotThrowAnyException();
        assertThat(matching(INVOICES, Invoice.class, tree)).containsExactlyInAnyOrder(
                big.getId(), late.getId(), lateBig.getId(), away.getId());

        // Nothing at all is still nothing at all, in both directions.
        assertThat(conditionJson.parse((String) null)).isNull();
        assertThat(conditionJson.parse("   ")).isNull();
        assertThat(conditionJson.parse("null")).isNull();
        assertThat(conditionJson.write(null)).isNull();
    }

    @Test
    void aJsonNodeThatIsNeitherAFilterNorAGroupIsRefusedByName() {
        assertThatThrownBy(() -> conditionJson.parse("{\"of\":[]}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A condition must be either a filter or a group: {\"of\":[]}");

        assertThatThrownBy(() -> conditionJson.parse(
                "{\"op\":\"AND\",\"of\":[{\"field\":\"balance\"}]}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A condition must be either a filter or a group: {\"field\":\"balance\"}");

        assertThatThrownBy(() -> conditionJson.parse("{\"op\":\"XOR\",\"of\":[]}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown condition connector: XOR (expected AND or OR)");

        assertThatThrownBy(() -> conditionJson.parse("{\"op\":\"AND\",\"of\":{}}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("\"of\" must be a list");

        // A malformed leaf is FilterSpec's own existing 400, not a second one invented here.
        assertThatThrownBy(() -> conditionJson.parse("{\"filter\":\"balancegt50000\"}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Malformed filter (expected field:operator:value): balancegt50000");

        assertThatThrownBy(() -> conditionJson.parse("{\"op\":"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Conditions are not valid JSON:");
    }

    // ---------------------------------------------------------------- A2 + B1: the axis still wins

    @Test
    void theRegionPredicateStillBoundsAListWhoseConditionTreeMatchesEverything() {
        // The cashier works in HQ only. The AWAY invoice is in NORTH.
        actAs(cashier);
        assertThat(matching(INVOICES, Invoice.class, and())).containsExactlyInAnyOrder(
                big.getId(), small.getId(), late.getId(), lateBig.getId(), paidOff.getId());

        // A tree that names the other branch's row by its own id still cannot reach it: the region
        // predicate is added by the executor itself, ahead of every scope factory, so conditions
        // can only ever narrow (A2, B1).
        assertThat(matching(INVOICES, Invoice.class,
                or(leaf("id:eq:" + away.getId()), leaf("balance:gt:1000000"))))
                .isEmpty();

        // And it is genuinely the region doing it, not the fixture: the administrator, who works
        // everywhere, reads the same tree and gets the row.
        actAs(admin);
        assertThat(matching(INVOICES, Invoice.class,
                or(leaf("id:eq:" + away.getId()), leaf("balance:gt:1000000"))))
                .containsExactly(away.getId());
    }

    // ---------------------------------------------------------------- helpers

    private Invoice invoice(Customer c, String number, String total, String paid,
                            InvoiceStatus status, LocalDate due) {
        return invoiceRepository.save(Invoice.builder()
                .invoiceNumber(number)
                .customer(c)
                .invoiceDate(RAISED)
                .dueDate(due)
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build());
    }

    /** Validate as a rule save would, then run as a rule run would. */
    private List<Long> matching(TableSchema schema, Class<?> type, ConditionNode tree) {
        Conditions.validate(tree, schema);
        return run(schema, type, tree);
    }

    /** Run WITHOUT validating, which is what a rule saved before a schema changed would do. */
    private List<Long> run(TableSchema schema, Class<?> type, ConditionNode tree) {
        List<PredicateFactory> scope = new ArrayList<>();
        PredicateFactory factory = Conditions.factory(tree, schema);
        // Deliberately guarded: factory(null) is null and the executor calls build() on every
        // element of the scope list (A2).
        if (factory != null) scope.add(factory);
        return queryExecutor.ids(type, schema,
                TableQuery.parseUnpaged(schema, "id,asc", List.of()), scope, 500);
    }

    private static ConditionNode leaf(String wire) {
        return new ConditionNode.Leaf(FilterSpec.parse(wire));
    }

    private static ConditionNode and(ConditionNode... of) {
        return new ConditionNode.Group(ConditionNode.Connector.AND, List.of(of));
    }

    private static ConditionNode or(ConditionNode... of) {
        return new ConditionNode.Group(ConditionNode.Connector.OR, List.of(of));
    }

    /** A tree whose deepest node sits at {@code levels}, counting the leaf's own level. */
    private static ConditionNode nest(int levels) {
        ConditionNode n = leaf("id:gt:0");
        for (int i = 1; i < levels; i++) n = and(n);
        return n;
    }

    private static ConditionNode wide(int leaves) {
        List<ConditionNode> of = new ArrayList<>();
        for (int i = 0; i < leaves; i++) of.add(leaf("id:gt:" + i));
        return new ConditionNode.Group(ConditionNode.Connector.AND, of);
    }

    private static String deepJson(int levels) {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            open.append("{\"op\":\"AND\",\"of\":[");
            close.append("]}");
        }
        return open + "{\"filter\":\"id:gt:0\"}" + close;
    }

    private static List<String> leaves(ConditionNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(ConditionNode node, List<String> out) {
        if (node instanceof ConditionNode.Leaf leaf) {
            out.add(leaf.spec().wire());
            return;
        }
        for (ConditionNode child : ((ConditionNode.Group) node).of()) collect(child, out);
    }
}
