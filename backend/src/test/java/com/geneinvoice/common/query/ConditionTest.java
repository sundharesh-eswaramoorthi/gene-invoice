package com.geneinvoice.common.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.eval.RuleEvaluator;
import com.geneinvoice.automation.rule.RuleDefinition;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A rule's condition (A2, §5.10.2): AND/OR over the list pages' own filters, meaning exactly what each filter means
 * on the list, checked whole when the rule is saved, and paged by id when a set is evaluated.
 *
 * <p>Forty days ago ({@link #past}) Acme (terms NET_15) was billed a1 (100) and a2 (600, 100 paid at once), both
 * overdue since; Gamma (NET_15) was billed g1 (100), paid in full and moved to NET_60 fifteen days ago. Today Acme is
 * billed a3 (200) and Beta (no terms of its own, so NET_30) b1 (700) and b2 (100, paid).
 */
class ConditionTest extends AutomationTestBase {

    @Autowired TableQueryExecutor queryExecutor;
    @Autowired RuleEvaluator evaluator;
    @Autowired PocService pocService;

    User admin;
    User cara;
    Product widget;
    Customer acme;
    Customer beta;
    Customer gamma;
    Long a1;
    Long a2;
    Long a3;
    Long b1;
    Long b2;
    Long g1;
    LocalDate past;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        Instant now = MovableClock.SHARED.instant();
        past = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(40);
        MovableClock.SHARED.set(past.atTime(10, 0).toInstant(ZoneOffset.UTC));
        widget = product("Widget", "100.00");
        acme = withTerm(customer("Acme Ltd"), PaymentTerm.NET_15);
        gamma = withTerm(customer("Gamma Co"), PaymentTerm.NET_15);
        actAs(admin);
        a1 = create(acme, 1, "call first");
        a2 = create(acme, 6, null);
        pay(acme, "100.00", a2);
        g1 = create(gamma, 1, null);
        MovableClock.SHARED.advance(Duration.ofDays(25));
        pay(gamma, "100.00", g1);
        Customer moved = customerRepository.findById(gamma.getId()).orElseThrow();
        moved.setPaymentTerm(PaymentTerm.NET_60);
        customerRepository.save(moved);
        MovableClock.SHARED.reset();
        beta = customer("Beta Co");
        actAs(admin);
        a3 = create(acme, 2, null);
        b1 = create(beta, 7, null);
        b2 = create(beta, 1, null);
        pay(beta, "100.00", b2);
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
    }

    private Customer withTerm(Customer c, PaymentTerm term) {
        c.setPaymentTerm(term);
        return customerRepository.save(c);
    }

    private Long create(Customer c, int quantity, String notes) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null, null, notes,
                seller.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, null)))).getId();
    }

    private Condition parse(Object json) {
        Map<String, String> errors = new LinkedHashMap<>();
        Condition c = ConditionJson.parse(objectMapper.valueToTree(json), "condition", errors);
        assertThat(errors).isEmpty();
        return c;
    }

    private Set<Long> matching(Class<?> type, TableSchema schema, Object json) {
        Condition c = parse(json);
        return new HashSet<>(queryExecutor.ids(type, schema, new TableQuery(0, 100, "id", true, List.of()),
                List.of(c.toPredicate(schema)), 100));
    }

    @Test
    void groupsNestAndOrAsWritten() {
        Set<Long> found = matching(Invoice.class, TableSchemas.INVOICES, and(
                leaf("status:in:UNPAID,PARTIALLY_PAID"),
                or(leaf("overdue:eq:true"), leaf("balance:gt:500"))));
        assertThat(found).as("overdue a1 and a2, and b1 for its balance; a3 is neither, b2 is paid")
                .containsExactlyInAnyOrder(a1, a2, b1);

        Set<Long> either = matching(Invoice.class, TableSchemas.INVOICES, or(
                leaf("status:eq:FULLY_PAID"), and(leaf("overdue:eq:false"), leaf("balance:lt:250"))));
        assertThat(either).containsExactlyInAnyOrder(b2, a3, g1);
    }

    @Test
    void anAndOnlyTreeMatchesTheListAskedWithTheSameFilters() throws Exception {
        List<String> filters = List.of("status:in:UNPAID,PARTIALLY_PAID", "balance:gt:150", "customerName:contains:c");
        Set<Long> byCondition = matching(Invoice.class, TableSchemas.INVOICES,
                and(filters.stream().map(f -> (Object) leaf(f)).toArray()));
        var request = get("/api/invoices").with(as(admin)).param("size", "50");
        filters.forEach(f -> request.param("filter", f));
        JsonNode page = body(mockMvc.perform(request).andExpect(status().isOk()));
        Set<Long> byList = new HashSet<>();
        page.get("content").forEach(n -> byList.add(n.get("id").asLong()));
        assertThat(byCondition).isEqualTo(byList).containsExactlyInAnyOrder(a2, a3, b1);
    }

    @Test
    void notEqualKeepsRecordsWithNoValue() {
        assertThat(matching(Invoice.class, TableSchemas.INVOICES, leaf("notes:neq:call first")))
                .as("every invoice but a1, the ones without notes included")
                .containsExactlyInAnyOrder(a2, a3, b1, b2, g1);
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("phone:neq:12345")))
                .containsExactlyInAnyOrder(acme.getId(), beta.getId(), gamma.getId());
    }

    @Test
    void derivedColumnsAndSeatsMeanWhatTheListMeans() {
        assertThat(matching(Invoice.class, TableSchemas.INVOICES, leaf("overdue:eq:true")))
                .containsExactlyInAnyOrder(a1, a2);
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("collectionPocUserId:eq:" + cara.getId())))
                .containsExactly(acme.getId());
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("collectionPocUserId:isEmpty:")))
                .containsExactlyInAnyOrder(beta.getId(), gamma.getId());
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("outstanding:gt:750")))
                .as("Acme owes 800, Beta 700").containsExactly(acme.getId());
    }

    /** The fields placeholders already named, now filters too (PC-11), live and as of a date. */
    @Test
    void paymentTermAndOverdueAmountFilterLiveAndAsOfADate() throws Exception {
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("paymentTerm:eq:NET_15")))
                .containsExactly(acme.getId());
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("paymentTerm:in:NET_60,NET_30")))
                .as("Beta has no terms of its own").containsExactly(gamma.getId());
        assertThat(matching(Invoice.class, TableSchemas.INVOICES, leaf("paymentTerm:eq:NET_15")))
                .containsExactlyInAnyOrder(a1, a2, a3, g1);
        assertThat(matching(Invoice.class, TableSchemas.INVOICES, leaf("paymentTerm:eq:NET_30")))
                .containsExactlyInAnyOrder(b1, b2);
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("overdueAmount:gt:0")))
                .as("a1's 100 and a2's 500").containsExactly(acme.getId());
        assertThat(matching(Customer.class, TableSchemas.CUSTOMERS, leaf("overdueAmount:eq:600")))
                .containsExactly(acme.getId());

        // Twenty days after the bills, Gamma still owed g1 (overdue since day 15) and was on NET_15.
        LocalDate then = past.plusDays(20);
        assertThat(customerIds(then, "overdueAmount:gt:0")).containsExactlyInAnyOrder(acme.getId(), gamma.getId());
        assertThat(customerIds(then, "paymentTerm:eq:NET_15")).containsExactlyInAnyOrder(acme.getId(), gamma.getId());
        assertThat(customerIds(null, "overdueAmount:gt:0")).containsExactly(acme.getId());
        assertThat(customerIds(null, "paymentTerm:eq:NET_15")).containsExactly(acme.getId());
        assertThat(customerIds(past.plusDays(5), "overdueAmount:gt:0"))
                .as("nothing was overdue five days after the bills").isEmpty();
    }

    private List<Long> customerIds(LocalDate day, String filter) throws Exception {
        var request = get("/api/customers").with(as(admin)).param("filter", filter);
        if (day != null) request.param("asOf", day.toString());
        JsonNode page = body(mockMvc.perform(request).andExpect(status().isOk()));
        return StreamSupport.stream(page.get("content").spliterator(), false).map(n -> n.get("id").asLong()).toList();
    }

    @Test
    void anUnknownColumnABadOperatorOrABadValueIsRefusedAtSaveUnderItsPath() throws Exception {
        Map<String, Object> body = rule("Bad", "INVOICE", "ENTITY_UPDATED", and(
                leaf("status:in:UNPAID"),
                leaf("nope:eq:1"),
                or(leaf("overdue:gt:1"), leaf("status:eq:NOPE"))), task("Chase"));
        JsonNode refused = body(send(post(RULES), admin, body).andExpect(status().isBadRequest()));
        JsonNode errors = refused.get("fieldErrors");
        assertThat(errors.get("condition.children[1].filter").asText()).contains("Unknown column: nope");
        assertThat(errors.get("condition.children[2].children[0].filter").asText())
                .contains("Operator gt is not valid for column overdue");
        assertThat(errors.has("condition.children[2].children[1].filter"))
                .as("a value is checked once the columns are right").isFalse();

        JsonNode badValue = body(send(post(RULES), admin, rule("Bad", "INVOICE", "ENTITY_UPDATED",
                or(leaf("status:in:UNPAID"), and(leaf("balance:gt:1"), leaf("status:eq:NOPE"))), task("Chase")))
                .andExpect(status().isBadRequest()));
        assertThat(badValue.get("fieldErrors").get("condition.children[1].children[1].filter").asText())
                .contains("Invalid value for column status");
        JsonNode malformed = body(send(post(RULES), admin, rule("Bad", "INVOICE", "ENTITY_UPDATED",
                Map.of("op", "XOR", "children", List.of(leaf("status"))), task("Chase")))
                .andExpect(status().isBadRequest()));
        assertThat(malformed.get("fieldErrors").get("condition.op").asText()).isEqualTo("A group is AND or OR");
        assertThat(automationRuleRepository.count()).isZero();
    }

    @Test
    void anEmptyGroupIsRefused() throws Exception {
        JsonNode refused = body(send(post(RULES), admin, rule("Empty", "INVOICE", "ENTITY_UPDATED",
                and(leaf("status:in:UNPAID"), Map.of("op", "OR", "children", List.of())), task("Chase")))
                .andExpect(status().isBadRequest()));
        assertThat(refused.get("fieldErrors").get("condition.children[1]").asText())
                .isEqualTo("A group needs at least one condition");
        assertThat(automationRuleRepository.count()).isZero();
    }

    @Test
    void groupsGoAtMostFourDeepWithThirtyFiltersAndTwentyToAGroup() {
        Object deep = leaf("status:in:UNPAID");
        for (int i = 0; i < 4; i++) deep = and(deep);
        assertThat(errors(deep)).isEmpty();
        assertThat(errors(and(deep))).containsValue("Groups go at most 4 deep");

        List<Object> thirty = new ArrayList<>(Collections.nCopies(15, leaf("balance:gt:1")));
        assertThat(errors(and(and(thirty.toArray()), and(thirty.toArray())))).isEmpty();
        List<Object> twentyOne = new ArrayList<>(Collections.nCopies(21, leaf("balance:gt:1")));
        assertThat(errors(or(twentyOne.toArray()))).containsEntry("condition", "A group has at most 20 conditions");
        List<Object> sixteen = new ArrayList<>(Collections.nCopies(16, leaf("balance:gt:1")));
        assertThat(errors(and(and(sixteen.toArray()), and(thirty.toArray()))))
                .containsEntry("condition", "A condition has at most 30 filters");
    }

    private Map<String, String> errors(Object json) {
        Map<String, String> errors = new LinkedHashMap<>();
        parse(json).validate(TableSchemas.INVOICES, "condition", errors);
        return errors;
    }

    /** A set is read a page at a time by id, so every record is visited once and only once (R18). */
    @Test
    void pagingByIdVisitsEveryMatchOnce() {
        Customer many = customer("Many Co");
        actAs(admin);
        List<Long> made = new ArrayList<>();
        for (int i = 0; i < 10; i++) made.add(create(many, 1, null));
        RuleDefinition rule = new RuleDefinition(null, "All of Many", null, true, TriggerKind.MANUAL,
                TriggerEntity.INVOICE, null, null, ZoneId.of("UTC"), "ONE", Set.of(),
                parse(leaf("customerId:eq:" + many.getId())), List.of(), 1000, admin.getId());

        List<Long> seen = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        long after = 0;
        while (true) {
            List<Long> page = evaluator.matchingPage(rule, after, 3, RuleEvaluator.Restriction.NONE);
            if (page.isEmpty()) break;
            pages.add(page.size());
            seen.addAll(page);
            after = page.get(page.size() - 1);
        }
        assertThat(pages).containsExactly(3, 3, 3, 1);
        assertThat(seen).containsExactlyElementsOf(made);
        assertThat(evaluator.count(rule)).isEqualTo(10);
        assertThat(evaluator.matchingPage(rule, 0, 50, RuleEvaluator.Restriction.ids(List.of(made.get(4), a1))))
                .as("chosen records, and only those the condition matches").containsExactly(made.get(4));
    }
}
