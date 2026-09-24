package com.geneinvoice.asof;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionSchemas;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE SIX AS-OF TABLE SCHEMAS, and the claim they exist to make true: a list asked as of a date
 * offers exactly the columns, the filters and the sorts its live twin offers, and answers them
 * from the mirror instead of from today's rows (B3).
 *
 * <p>WHAT IS STRUCTURAL HERE AND WHAT IS BEHAVIOURAL, said up front so nobody reads fourteen green
 * tests as fourteen proofs of the same strength. The first five are structural: they compare the
 * twins against their live schemas and resolve every path and every custom filter against the real
 * Hibernate metamodel, which is what catches a misspelled attribute — a ColumnDef path is a lambda
 * over strings and nothing about it is checked at compile time. The rest are behavioural and each
 * one FAILS if the switch it is about is taken out: the region column read at a date, the seat
 * filter read at a date, the POC book read at a date, what an account owed at a date, which
 * invoices a promise covered at a date, whether a change was outstanding at a date, and the two
 * wire fields that tell a client any of this is on offer.
 *
 * <p>Every timeline below is hand-written into the mirrors rather than produced by sleeping
 * between saves: the writer dates a row at the wall clock, so a test that wanted a January version
 * and a March version of one invoice could not get one any other way (B3).
 */
class AsOfSchemasTest extends IntegrationTestBase {

    @Autowired TableQueryExecutor queryExecutor;
    @Autowired ScopeResolver scopeResolver;
    @Autowired EntityManagerFactory entityManagerFactory;

    /** The live schema each twin is the as-of reading of. */
    private static final Map<TableSchema, TableSchema> PAIRS = Map.of(
            HistorySchemas.INVOICES, TableSchemas.INVOICES,
            HistorySchemas.CUSTOMERS, TableSchemas.CUSTOMERS,
            HistorySchemas.PAYMENTS, TableSchemas.PAYMENTS,
            HistorySchemas.PROMISES, TableSchemas.PROMISES,
            HistorySchemas.DISPUTES, TableSchemas.DISPUTES,
            HistorySchemas.TASKS, com.geneinvoice.task.TaskSchemas.TASKS);

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 15);
    private static final LocalDate FEBRUARY = LocalDate.of(2026, 2, 15);
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 15);

    private static Instant at(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // ---- (a) the two schemas describe the same table ------------------------------------------

    /**
     * A list that offered one column fewer as of a date would be a second product wearing the
     * first one's name: the filter dialog, the CSV header and every saved view are built from this
     * metadata, and a column that quietly vanished under ?asOf would look like a column that had
     * no value rather than one nobody asked (B3).
     */
    @Test
    void everyAsOfSchemaHasExactlyTheColumnsItsLiveSchemaHas() {
        assertThat(HistorySchemas.all()).hasSize(6);
        PAIRS.forEach((asOf, live) -> {
            assertThat(asOf.columns().stream().map(ColumnDef::name).toList())
                    .describedAs("the as-of twin of %s offers the same columns in the same order",
                            live.entity())
                    .isEqualTo(live.columns().stream().map(ColumnDef::name).toList());
            // The twin answers under the LIVE name, so a locked chip, an appliedFilters echo and
            // every "Unknown column" message read identically on both paths (B3).
            assertThat(asOf.entity()).isEqualTo(live.entity());
            assertThat(asOf.defaultSort()).isEqualTo(live.defaultSort());
        });
    }

    @Test
    void everyPairOfColumnDefsAgreesOnTypeSortabilityFilterabilityAndReferenceKind() {
        PAIRS.forEach((asOf, live) -> {
            for (ColumnDef liveColumn : live.columns()) {
                ColumnDef twin = asOf.require(liveColumn.name());
                String where = live.entity() + "." + liveColumn.name();
                assertThat(twin.label()).describedAs(where + " label").isEqualTo(liveColumn.label());
                assertThat(twin.type()).describedAs(where + " type").isEqualTo(liveColumn.type());
                assertThat(twin.sortable()).describedAs(where + " sortable")
                        .isEqualTo(liveColumn.sortable());
                assertThat(twin.filterable()).describedAs(where + " filterable")
                        .isEqualTo(liveColumn.filterable());
                assertThat(twin.enumValues()).describedAs(where + " enumValues")
                        .isEqualTo(liveColumn.enumValues());
                assertThat(twin.referenceKind()).describedAs(where + " referenceKind")
                        .isEqualTo(liveColumn.referenceKind());
                assertThat(twin.pocRestricted()).describedAs(where + " pocRestricted")
                        .isEqualTo(liveColumn.pocRestricted());
            }
        });
    }

    /**
     * RESOLVED, not compared against a list of names. Both halves matter: the PATH is what every
     * sort and the boot gate use, and the custom FILTER is what the boot gate does NOT reach — it
     * only resolves paths — so a subquery naming an attribute no mirror has would first be seen by
     * whoever filtered by it in production (B3).
     */
    @Test
    void everyAsOfColumnResolvesAnAttributeTheMirrorActuallyHas() {
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        int paths = 0;
        int filters = 0;
        for (TableSchema twin : HistorySchemas.all()) {
            for (ColumnDef column : twin.columns()) {
                String where = twin.entity() + " as of a date: " + column.name();
                assertThatCode(() -> {
                    CriteriaQuery<Object> q = cb.createQuery();
                    Root<?> root = q.from(twin.entityType());
                    column.path().resolve(root, q, cb);
                }).describedAs(where + " path").doesNotThrowAnyException();
                paths++;

                if (column.customFilter() == null) continue;
                for (FilterSpec spec : probes(column)) {
                    assertThatCode(() -> {
                        CriteriaQuery<Object> q = cb.createQuery();
                        Root<?> root = q.from(twin.entityType());
                        column.customFilter().resolve(spec, root, q, cb);
                    }).describedAs(where + " filter " + spec.wire()).doesNotThrowAnyException();
                    filters++;
                }
            }
        }
        // Counted, so a probe builder that quietly produced nothing could not leave this test
        // green while resolving no subquery at all (B3).
        assertThat(paths).describedAs("as-of column paths resolved").isGreaterThan(80);
        assertThat(filters).describedAs("as-of custom filters resolved").isGreaterThan(40);
    }

    /** One FilterSpec per operator the column's type offers, so every switch arm is resolved. */
    private static List<FilterSpec> probes(ColumnDef column) {
        List<FilterSpec> specs = new ArrayList<>();
        for (var op : column.type().operators()) {
            String value = switch (column.type()) {
                case BOOLEAN -> "true";
                case DATE -> "2026-01-15";
                default -> "1";
            };
            try {
                specs.add(FilterSpec.parse(column.name() + ":" + op.wire() + ":"
                        + (op.arity() == 0 ? "" : value)));
            } catch (RuntimeException ignored) {
                // An operator whose wire form this crude builder cannot spell (a range needs two
                // values) is covered by the arms that follow it; the point is to reach the
                // subquery, not to enumerate the parser (B3).
            }
        }
        return specs;
    }

    /**
     * The one field AsOfSchemaCheck lets a twin disagree with its live column on, and the only way
     * a client can be told that a value it is about to render is not an answer about the date it
     * asked for (B3).
     */
    @Test
    void theFourDenormalisedLabelColumnsSayTheyAreCurrentAndTheRestSayExact() {
        Set<String> current = new LinkedHashSet<>();
        for (TableSchema twin : HistorySchemas.all()) {
            for (ColumnDef column : twin.columns()) {
                if ("CURRENT".equals(column.asOfMode())) current.add(column.name());
                else assertThat(column.asOfMode())
                        .describedAs(twin.entity() + "." + column.name())
                        .isEqualTo("EXACT");
            }
        }
        // FOUR distinct columns, seven instances of them: customerName on invoices, payments and
        // promises, salesPocName on invoices, collectionPocName on payments and promises, and the
        // task assignee — whose seats are the one thing here that is not mirrored at all (B3).
        assertThat(current).containsExactlyInAnyOrder(
                "customerName", "salesPocName", "collectionPocName", "assigneeUserId");
        assertThat(HistorySchemas.INVOICES.require("customerName").asOfMode()).isEqualTo("CURRENT");
        assertThat(HistorySchemas.TASKS.require("assigneeUserId").asOfMode()).isEqualTo("CURRENT");
        // And every LIVE column still says EXACT, because a live list is always as of now.
        assertThat(TableSchemas.INVOICES.require("customerName").asOfMode()).isEqualTo("EXACT");
    }

    /**
     * ONE pair of region columns and not twelve, which is what blueprint conflict 86 buys. The
     * shared pair has to resolve on all six mirrors, and the mirrors do not agree about much: one
     * of them IS the customer (CustomerHistory maps customer_id twice so that both readings work)
     * and the other five carry it as an ordinary flat foreign key (B1, B3).
     */
    @Test
    void theSharedRegionColumnPairResolvesOnAllSixTwins() {
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        for (TableSchema twin : HistorySchemas.all()) {
            assertThat(twin.require("regionId"))
                    .describedAs(twin.entity() + " shares the one as-of regionId column")
                    .isSameAs(RegionSchemas.AS_OF_REGION_ID);
            assertThat(twin.require("regionName"))
                    .describedAs(twin.entity() + " shares one of the two as-of regionName columns")
                    .isIn(RegionSchemas.AS_OF_REGION_NAME, RegionSchemas.AS_OF_REGION_NAME_UNSORTED);

            CriteriaQuery<Object> q = cb.createQuery();
            Root<?> root = q.from(twin.entityType());
            assertThatCode(() -> {
                twin.require("regionId").path().resolve(root, q, cb);
                twin.require("regionName").path().resolve(root, q, cb);
                twin.require("regionId").customFilter()
                        .resolve(FilterSpec.parse("regionId:in:1,2"), root, q, cb);
            }).describedAs(twin.entity() + " over " + twin.entityType().getSimpleName())
                    .doesNotThrowAnyException();
        }
    }

    // ---- (b) the twins actually answer as of the date -----------------------------------------

    /**
     * WHICH REGION A RECORD BELONGED TO THEN — the PRD clause, asked as a filter on the list and
     * not only as the invisible axis B1 already ANDs in. The account moved WEST in February; the
     * January list answers HQ and the March list answers WEST, from customer_region_history and
     * from nothing else (B1, B3).
     */
    @Test
    void aRegionFilterAsOfADateReadsThePlacementLedgerAndNotTheAccountsCurrentRegion() {
        actAs(admin());
        Region west = region("WEST");
        // Built straight into WEST, which is where it is NOW — Customer.setRegion is
        // package-private on purpose, so that only RegionCustodyService can move an account.
        Customer moved = customerRepository.saveAndFlush(
                Customer.builder().name("Moved Ltd").region(west).build());
        // The ledger: HQ until February, WEST from February. The live row says WEST, which is the
        // answer that must NOT come back for January.
        placement(moved, defaultRegion(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        placement(moved, west, LocalDate.of(2026, 2, 1), null);

        clearMirrors();
        customerHistoryRepository.saveAndFlush(CustomerHistory.builder()
                .id(moved.getId()).validFrom(at(LocalDate.of(2026, 1, 1)))
                .validTo(com.geneinvoice.common.asof.AsOf.OPEN)
                .name("Moved Ltd").creditBalance(BigDecimal.ZERO).build());

        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(customerIdsAsOf("regionId:eq:" + defaultRegion().getId()))
                    .describedAs("in January the account was in HQ")
                    .containsExactly(moved.getId());
            assertThat(customerIdsAsOf("regionId:eq:" + west.getId())).isEmpty();
        }
        try (AsOfContext.Handle ignored = AsOfContext.open(MARCH)) {
            assertThat(customerIdsAsOf("regionId:eq:" + west.getId()))
                    .describedAs("by March it had moved")
                    .containsExactly(moved.getId());
            assertThat(customerIdsAsOf("regionId:eq:" + defaultRegion().getId())).isEmpty();
        }
        // And the NAME column agrees with the id column at both dates.
        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(customerIdsAsOf("regionName:eq:" + defaultRegion().getName()))
                    .containsExactly(moved.getId());
        }
    }

    /**
     * WHOSE DESK THE ACCOUNT WAS ON THEN. The seat was handed over in February, so the January
     * list still finds it under the person who held it in January — which is the filter column
     * half of "my book as of then" (B3).
     */
    @Test
    void theSeatFilterAsOfADateFindsTheAccountsThatWereOnThatPersonsDeskThen() {
        actAs(admin());
        User before = user("seat-before", "CUSTOMER_SUCCESS_POC");
        User after = user("seat-after", "CUSTOMER_SUCCESS_POC");
        Customer account = customer("Handed Over Ltd");

        clearMirrors();
        customerHistoryRepository.saveAndFlush(CustomerHistory.builder()
                .id(account.getId()).validFrom(at(LocalDate.of(2026, 1, 1)))
                .validTo(com.geneinvoice.common.asof.AsOf.OPEN)
                .name("Handed Over Ltd").creditBalance(BigDecimal.ZERO).build());
        seat(1L, account, before, at(LocalDate.of(2026, 1, 1)), at(LocalDate.of(2026, 2, 1)));
        seat(2L, account, after, at(LocalDate.of(2026, 2, 1)), com.geneinvoice.common.asof.AsOf.OPEN);

        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(customerIdsAsOf("successPocUserId:eq:" + before.getId()))
                    .containsExactly(account.getId());
            assertThat(customerIdsAsOf("successPocUserId:eq:" + after.getId())).isEmpty();
        }
        try (AsOfContext.Handle ignored = AsOfContext.open(MARCH)) {
            assertThat(customerIdsAsOf("successPocUserId:eq:" + after.getId()))
                    .containsExactly(account.getId());
            assertThat(customerIdsAsOf("successPocUserId:eq:" + before.getId())).isEmpty();
        }
    }

    /**
     * THE POC BOOK AS OF THEN, which is ScopeResolver's half of the same fact and the half a
     * person cannot ask for: it is ANDed into their list whether they filter or not. A Customer
     * Success POC who lost the account in February still sees it, as of January, as theirs — and
     * the person who has it now does not (B3, AUTH-08).
     */
    @Test
    void theCustomerBookAsOfADateIsTheBookThatPersonHeldThen() {
        // SALES_POC and not CUSTOMER_SUCCESS_POC: the success and collection roles hold
        // SCOPE_OVERRIDE, so forCustomers short-circuits to an empty scope for them and this test
        // would have passed without ever building a book predicate at all (B3).
        User before = user("book-before", "SALES_POC");
        User after = user("book-after", "SALES_POC");
        actAs(admin());
        Customer account = customer("Book Ltd");
        // A second account that was never anybody's, so "the book" and "everything" are different
        // answers and these assertions can tell them apart.
        Customer other = customer("Somebody Elses Ltd");
        placement(account, defaultRegion(), LocalDate.of(2026, 1, 1), null);
        placement(other, defaultRegion(), LocalDate.of(2026, 1, 1), null);
        // TODAY the account is on `after`'s desk.
        customerPocRepository.saveAndFlush(com.geneinvoice.poc.CustomerPoc.builder()
                .customer(account).user(after).pocType(PocType.SALES).build());

        clearMirrors();
        for (Customer c : List.of(account, other)) {
            customerHistoryRepository.saveAndFlush(CustomerHistory.builder()
                    .id(c.getId()).validFrom(at(LocalDate.of(2026, 1, 1)))
                    .validTo(com.geneinvoice.common.asof.AsOf.OPEN)
                    .name(c.getName()).creditBalance(BigDecimal.ZERO).build());
        }
        // IN JANUARY IT WAS ON `before`'s. The seat was handed over on 1 February.
        seat(1L, account, before, at(LocalDate.of(2026, 1, 1)), at(LocalDate.of(2026, 2, 1)));
        seat(2L, account, after, at(LocalDate.of(2026, 2, 1)), com.geneinvoice.common.asof.AsOf.OPEN);

        actAs(before);
        assertThat(bookedCustomerIdsLive())
                .describedAs("live, they hold nothing")
                .isEmpty();
        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(bookedCustomerIdsAsOf())
                    .describedAs("as of January the account was theirs")
                    .containsExactly(account.getId());
        }
        try (AsOfContext.Handle ignored = AsOfContext.open(MARCH)) {
            assertThat(bookedCustomerIdsAsOf())
                    .describedAs("they had handed it over by March")
                    .isEmpty();
        }

        actAs(after);
        assertThat(bookedCustomerIdsLive())
                .describedAs("live, the seat they hold now IS their book, and it is not everything")
                .containsExactly(account.getId());
        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(bookedCustomerIdsAsOf())
                    .describedAs("it was not theirs in January, whoever holds it now")
                    .isEmpty();
        }
    }

    /**
     * WHAT THE ACCOUNT OWED THEN. The invoice was 100 unpaid in January and paid off in February,
     * so the January answer is 100 and the March answer is 0 — the same correlated sum, taken over
     * the invoice mirror with the interval clause inside it (B3).
     */
    @Test
    void whatAnAccountOwedThenIsSummedFromTheInvoicesAsTheyStoodThen() {
        actAs(admin());
        Customer account = customer("Owing Ltd");
        Invoice invoice = invoiceRepository.saveAndFlush(Invoice.builder()
                .customer(account).invoiceNumber("INV-OWE").invoiceDate(Instant.now())
                .dueDate(LocalDate.of(2026, 2, 1)).total(new BigDecimal("100.00"))
                .paidAmount(BigDecimal.ZERO).status(InvoiceStatus.UNPAID).build());

        clearMirrors();
        customerHistoryRepository.saveAndFlush(CustomerHistory.builder()
                .id(account.getId()).validFrom(at(LocalDate.of(2026, 1, 1)))
                .validTo(com.geneinvoice.common.asof.AsOf.OPEN)
                .name("Owing Ltd").creditBalance(BigDecimal.ZERO).build());
        invoiceVersion(invoice, account, at(LocalDate.of(2026, 1, 1)), at(LocalDate.of(2026, 2, 1)),
                "0.00", InvoiceStatus.UNPAID, null);
        invoiceVersion(invoice, account, at(LocalDate.of(2026, 2, 1)),
                com.geneinvoice.common.asof.AsOf.OPEN, "100.00", InvoiceStatus.FULLY_PAID, null);

        assertThat(outstandingAsOf(JANUARY, account)).isEqualByComparingTo("100.00");
        assertThat(outstandingAsOf(MARCH, account)).isEqualByComparingTo("0.00");
    }

    /**
     * WHICH INVOICES A PROMISE COVERED THEN. The live column walks a @ManyToMany that a promise
     * mirror has not got; the link mirror is flat and interval-versioned, so the coverage is a
     * question with a different answer in January and in March (B3).
     */
    @Test
    void aPromiseCoveredTheInvoicesItCoveredThenAndNotTheOnesItCoversNow() {
        actAs(admin());
        Customer account = customer("Promising Ltd");
        PaymentPromise promise = promiseRepository.saveAndFlush(PaymentPromise.builder()
                .customer(account).amount(new BigDecimal("50.00"))
                .fulfilledAmount(BigDecimal.ZERO).promisedDate(LocalDate.of(2026, 3, 1))
                .status(PromiseStatus.OPEN).build());

        clearMirrors();
        promiseHistoryRepository.saveAndFlush(PromiseHistory.builder()
                .id(promise.getId()).validFrom(at(LocalDate.of(2026, 1, 1)))
                .validTo(com.geneinvoice.common.asof.AsOf.OPEN)
                .customerId(account.getId()).customerName("Promising Ltd")
                .amount(new BigDecimal("50.00")).fulfilledAmount(BigDecimal.ZERO)
                .promisedDate(LocalDate.of(2026, 3, 1)).status(PromiseStatus.OPEN)
                .statusOverridden(false).build());
        // It covered invoice 7001 until February and invoice 7002 from February.
        promiseInvoiceHistoryRepository.saveAndFlush(PromiseInvoiceHistory.builder()
                .id(promise.getId()).invoiceId(7001L)
                .validFrom(at(LocalDate.of(2026, 1, 1))).validTo(at(LocalDate.of(2026, 2, 1)))
                .build());
        promiseInvoiceHistoryRepository.saveAndFlush(PromiseInvoiceHistory.builder()
                .id(promise.getId()).invoiceId(7002L)
                .validFrom(at(LocalDate.of(2026, 2, 1)))
                .validTo(com.geneinvoice.common.asof.AsOf.OPEN).build());

        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(promiseIdsAsOf("invoiceId:eq:7001")).containsExactly(promise.getId());
            assertThat(promiseIdsAsOf("invoiceId:eq:7002")).isEmpty();
        }
        try (AsOfContext.Handle ignored = AsOfContext.open(MARCH)) {
            assertThat(promiseIdsAsOf("invoiceId:eq:7002")).containsExactly(promise.getId());
            assertThat(promiseIdsAsOf("invoiceId:eq:7001")).isEmpty();
        }
    }

    /**
     * WHICH APPROVALS WERE OUTSTANDING THEN — the PRD's third clause, and the one delivery in the
     * whole feature that needs no mirror table at all: pending_changes is append-only and carries
     * requestedAt and decidedAt, so it is already its own history (B2, B3).
     *
     * <p>Both readings are asserted, because they are two different code paths that must not
     * disagree: the filter chip (an EXISTS in ApprovalSchemas) and the batched flag lookup that
     * fills the DTO for a whole page (a JPQL query in PendingChangeRepository).
     */
    @Test
    void theApprovalPendingResolverReadsTheDecisionLogWhenADateIsInForce() {
        actAs(admin());
        Customer account = customer("Held Ltd");
        Invoice invoice = invoiceRepository.saveAndFlush(Invoice.builder()
                .customer(account).invoiceNumber("INV-HELD").invoiceDate(Instant.now())
                .dueDate(LocalDate.of(2026, 2, 1)).total(new BigDecimal("10.00"))
                .paidAmount(BigDecimal.ZERO).status(InvoiceStatus.UNPAID).build());
        // Raised on 1 February, decided on 1 March, and therefore decided today.
        pendingChangeRepository.saveAndFlush(PendingChange.builder()
                .action(PendingAction.INVOICE_REPLACE_ITEMS).targetType(PendingTargetType.INVOICE)
                .targetId(invoice.getId()).customerId(account.getId())
                .regionId(defaultRegion().getId()).exposure(new BigDecimal("10.00"))
                .thresholdApplied(new BigDecimal("1.00")).payloadJson("{}")
                .summary("A change on INV-HELD").status(PendingChangeStatus.APPROVED)
                .requestedAt(at(LocalDate.of(2026, 2, 1)))
                .decidedAt(at(LocalDate.of(2026, 3, 1))).build());

        clearMirrors();
        invoiceVersion(invoice, account, at(LocalDate.of(2026, 1, 1)),
                com.geneinvoice.common.asof.AsOf.OPEN, "0.00", InvoiceStatus.UNPAID, null);

        // In January nobody had raised it yet.
        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(invoiceIdsAsOf("approvalPending:eq:true")).isEmpty();
            assertThat(pendingChangeRepository.openTargetIds(
                    PendingTargetType.INVOICE, List.of(invoice.getId()))).isEmpty();
        }
        // In February it was waiting for a second pair of eyes.
        try (AsOfContext.Handle ignored = AsOfContext.open(FEBRUARY)) {
            assertThat(invoiceIdsAsOf("approvalPending:eq:true")).containsExactly(invoice.getId());
            assertThat(invoiceIdsAsOf("approvalPending:eq:false")).isEmpty();
            assertThat(pendingChangeRepository.openTargetIds(
                    PendingTargetType.INVOICE, List.of(invoice.getId())))
                    .containsExactly(invoice.getId());
        }
        // By March it had been decided, and today it still is.
        try (AsOfContext.Handle ignored = AsOfContext.open(MARCH)) {
            assertThat(invoiceIdsAsOf("approvalPending:eq:true")).isEmpty();
        }
        assertThat(pendingChangeRepository.openTargetIds(
                PendingTargetType.INVOICE, List.of(invoice.getId()))).isEmpty();
    }

    /**
     * A customer login is pinned to its own account by the flat customer_id, which is the one
     * spelling that resolves on a live root AND on a mirror root. Three B3 units in a row reported
     * this line as unowned; without it every as-of list a customer could reach would be a 500
     * rather than an answer (B3).
     */
    @Test
    void aCustomerLoginIsPinnedToItsOwnAccountOnAMirrorRootToo() {
        actAs(admin());
        Customer mine = customer("Mine Ltd");
        Customer theirs = customer("Theirs Ltd");
        Invoice ours = invoiceRepository.saveAndFlush(Invoice.builder()
                .customer(mine).invoiceNumber("INV-MINE").invoiceDate(Instant.now())
                .dueDate(LocalDate.of(2026, 2, 1)).total(new BigDecimal("5.00"))
                .paidAmount(BigDecimal.ZERO).status(InvoiceStatus.UNPAID).build());
        Invoice notOurs = invoiceRepository.saveAndFlush(Invoice.builder()
                .customer(theirs).invoiceNumber("INV-THEIRS").invoiceDate(Instant.now())
                .dueDate(LocalDate.of(2026, 2, 1)).total(new BigDecimal("5.00"))
                .paidAmount(BigDecimal.ZERO).status(InvoiceStatus.UNPAID).build());
        User login = customerUser("their-login", mine.getId());

        clearMirrors();
        invoiceVersion(ours, mine, at(LocalDate.of(2026, 1, 1)),
                com.geneinvoice.common.asof.AsOf.OPEN, "0.00", InvoiceStatus.UNPAID, null);
        invoiceVersion(notOurs, theirs, at(LocalDate.of(2026, 1, 1)),
                com.geneinvoice.common.asof.AsOf.OPEN, "0.00", InvoiceStatus.UNPAID, null);

        actAs(login);
        try (AsOfContext.Handle ignored = AsOfContext.open(JANUARY)) {
            assertThat(queryExecutor.ids(InvoiceHistory.class, HistorySchemas.INVOICES,
                    TableQuery.parseUnpaged(HistorySchemas.INVOICES, null, List.of()),
                    scopeResolver.forInvoices().predicates(), 100))
                    .containsExactly(ours.getId());
        }
    }

    // ---- the wire ------------------------------------------------------------------------------

    /**
     * The two fields a client decides from: whether to offer a date picker on this list at all,
     * and which lists to offer it on. Both were hard-coded until this unit — asOfSupported was the
     * one literal S3 left behind and GET /api/as-of announced an empty list on purpose (B3).
     */
    @Test
    void theTableSchemaEndpointSaysWhichEntitiesCanBeAskedAsOfADate() throws Exception {
        User me = admin();
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfSupported").value(true));
        mockMvc.perform(get("/api/table-schemas/products").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfSupported").value(false));
        // The denormalised label says so per column, on the LIVE schema too — the flag describes
        // the column and the table says whether the table can be asked at all.
        mockMvc.perform(get("/api/table-schemas/invoices").with(as(me)))
                .andExpect(jsonPath("$.columns[?(@.name=='total')].asOfMode").value("EXACT"));

        mockMvc.perform(get("/api/as-of").with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities").isArray())
                .andExpect(jsonPath("$.entities[0]").value("invoices"))
                .andExpect(jsonPath("$.entities.length()").value(6));
    }

    // ---- the live path is untouched ---------------------------------------------------------

    /**
     * THE GUARD ON EVERYTHING ELSE. Three shared helpers were generalised rather than copied —
     * customerOutstanding, pocSeatPredicate and the two ScopeResolver seat subqueries — so the
     * live list, the live filter and the live book have to come out unchanged, and "unchanged" is
     * asserted against the SQL and not argued from the diff (B3).
     */
    @Test
    void theLivePlanIsUnchangedForEveryExistingColumn() throws Exception {
        // Every column that is not one of the 27 declared overrides is literally the same object.
        int overridden = 0;
        for (Map.Entry<TableSchema, TableSchema> pair : PAIRS.entrySet()) {
            for (ColumnDef live : pair.getValue().columns()) {
                if (pair.getKey().require(live.name()) != live) overridden++;
            }
        }
        assertThat(overridden).describedAs("hand-written overrides across the six twins")
                .isEqualTo(27);

        actAs(admin());
        User poc = user("live-poc", "CUSTOMER_SUCCESS_POC");
        Customer account = customer("Live Ltd");
        customerPocRepository.saveAndFlush(com.geneinvoice.poc.CustomerPoc.builder()
                .customer(account).user(poc).pocType(PocType.SUCCESS).build());

        List<String> sql = CountingStatements.capture(() ->
                assertThat(customerIdsLive("successPocUserId:eq:" + poc.getId()))
                        .containsExactly(account.getId()));
        String list = String.join("\n", sql);
        assertThat(list).describedAs("the live seat filter still reads customer_pocs")
                .contains("customer_pocs");
        assertThat(list).describedAs("and reads no mirror table at all")
                .doesNotContain("customer_poc_history").doesNotContain("invoice_history")
                .doesNotContain("customer_region_history");

        // The live outstanding column still sums the live invoices, for the same account, and the
        // correlated subquery still needs NO join: customerOutstanding now names the flat
        // invoices.customer_id that B3-BOOKROOT mapped rather than walking Invoice.customer, and
        // this is the measurement behind the claim that the plan did not move (B3).
        List<String> outstandingSql = CountingStatements.capture(() ->
                assertThat(customerIdsLive("id:eq:" + account.getId()))
                        .containsExactly(account.getId()));
        String sorted = outstandingSql.stream().filter(q -> q.contains("from invoices"))
                .findFirst().orElseThrow();
        assertThat(sorted).doesNotContain("invoice_history");
        assertThat(sorted.split("from invoices", -1).length - 1)
                .describedAs("one scan of invoices for the outstanding subquery, in %s", sorted)
                .isEqualTo(1);
        assertThat(sorted.substring(sorted.indexOf("from invoices")))
                .describedAs("and no join out of it, in %s", sorted)
                .doesNotContain("join");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private User admin() {
        return userRepository.findByUsername("admin").orElseThrow();
    }

    private List<Long> customerIdsAsOf(String filter) {
        return queryExecutor.ids(CustomerHistory.class, HistorySchemas.CUSTOMERS,
                TableQuery.parseUnpaged(HistorySchemas.CUSTOMERS, null, List.of(filter)),
                List.of(), 100);
    }

    private List<Long> customerIdsLive(String filter) {
        return queryExecutor.ids(Customer.class, TableSchemas.CUSTOMERS,
                TableQuery.parseUnpaged(TableSchemas.CUSTOMERS, "outstanding,desc", List.of(filter)),
                List.of(), 100);
    }

    private List<Long> invoiceIdsAsOf(String filter) {
        return queryExecutor.ids(InvoiceHistory.class, HistorySchemas.INVOICES,
                TableQuery.parseUnpaged(HistorySchemas.INVOICES, null, List.of(filter)),
                List.of(), 100);
    }

    private List<Long> promiseIdsAsOf(String filter) {
        return queryExecutor.ids(PromiseHistory.class, HistorySchemas.PROMISES,
                TableQuery.parseUnpaged(HistorySchemas.PROMISES, null, List.of(filter)),
                List.of(), 100);
    }

    private List<Long> bookedCustomerIdsLive() {
        return queryExecutor.ids(Customer.class, TableSchemas.CUSTOMERS,
                TableQuery.parseUnpaged(TableSchemas.CUSTOMERS, null, List.of()),
                scopeResolver.forCustomers().predicates(), 100);
    }

    private List<Long> bookedCustomerIdsAsOf() {
        return queryExecutor.ids(CustomerHistory.class, HistorySchemas.CUSTOMERS,
                TableQuery.parseUnpaged(HistorySchemas.CUSTOMERS, null, List.of()),
                scopeResolver.forCustomers().predicates(), 100);
    }

    private BigDecimal outstandingAsOf(LocalDate date, Customer account) {
        try (AsOfContext.Handle ignored = AsOfContext.open(date)) {
            Object[] row = queryExecutor.aggregate(CustomerHistory.class, HistorySchemas.CUSTOMERS,
                    TableQuery.parseUnpaged(HistorySchemas.CUSTOMERS, null,
                            List.of("id:eq:" + account.getId())),
                    List.of(),
                    (root, q, cb) -> List.of(
                            HistorySchemas.CUSTOMERS.require("outstanding").path().resolve(root, q, cb)));
            return row[0] == null ? BigDecimal.ZERO : (BigDecimal) row[0];
        }
    }

    private void placement(Customer c, Region region, LocalDate from, LocalDate to) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(region.getId()).validFrom(from).validTo(to).build());
    }

    private void seat(Long seatId, Customer c, User u, Instant from, Instant to) {
        customerPocHistoryRepository.saveAndFlush(CustomerPocHistory.builder()
                .id(seatId).customerId(c.getId()).userId(u.getId()).pocType(PocType.SUCCESS)
                .primary(false).validFrom(from).validTo(to).build());
    }

    private void invoiceVersion(Invoice invoice, Customer account, Instant from, Instant to,
                                String paid, InvoiceStatus status, User salesPoc) {
        invoiceHistoryRepository.saveAndFlush(InvoiceHistory.builder()
                .id(invoice.getId()).validFrom(from).validTo(to)
                .customerId(account.getId()).customerName(account.getName())
                .invoiceNumber(invoice.getInvoiceNumber()).invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate()).total(invoice.getTotal())
                .paidAmount(new BigDecimal(paid)).status(status)
                .salesPocUserId(salesPoc == null ? null : salesPoc.getId())
                .salesPocName(salesPoc == null ? null : salesPoc.getFullName())
                .createdAt(Instant.now()).build());
    }

    /**
     * Every fixture save writes its own mirror row at the WALL clock, so a test that wants a
     * January version of a record has to take those away first. The same four lines HistoryWriteTest
     * and HistoryRegistryTest each keep locally (B3).
     */
    private void clearMirrors() {
        promiseInvoiceHistoryRepository.deleteAll();
        promisePaymentHistoryRepository.deleteAll();
        invoiceItemHistoryRepository.deleteAll();
        paymentAllocationHistoryRepository.deleteAll();
        taskHistoryRepository.deleteAll();
        disputeHistoryRepository.deleteAll();
        customerPocHistoryRepository.deleteAll();
        promiseHistoryRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        invoiceHistoryRepository.deleteAll();
        customerHistoryRepository.deleteAll();
    }
}
