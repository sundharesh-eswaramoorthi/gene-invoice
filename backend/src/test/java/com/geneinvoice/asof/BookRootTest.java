package com.geneinvoice.asof;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The POC book named against the flat foreign key rather than walked through the association.
 *
 * <p>WHY IT MATTERS, AND IT IS NOT THE REASON THE DESIGN GAVE. B3's design expected this unit to
 * fix a live bug: {@code ColumnDef.referenceId("salesPoc")} is {@code root.get("salesPoc").get("id")},
 * which under plain JPA semantics is an INNER join to users, so {@code salesPocUserId:isEmpty:}
 * would have matched nothing and the one filter that finds unassigned work would have found none
 * of it. On Hibernate 6.5 that is NOT what happens: the association-id path is folded onto the
 * owning side's foreign key and the emitted SQL is already
 * {@code invoices.sales_poc_user_id is null}, with no join. Measured with CountingStatements
 * against this codebase before the change, and every test in this class passed before it. The
 * behaviour change B3 declared therefore does not exist here, and saying so is worth more than a
 * test that quietly agrees with the wrong story (B3).
 *
 * <p>What DOES change is structural, and B3 rests on it entirely: the three book predicates and
 * the three ColumnDefs now name an attribute that a mirror row can also offer. A history row maps
 * its foreign keys as plain Longs and has no {@code Customer} or {@code User} to walk, so
 * {@code root.get("salesPoc").get("id")} would not resolve against it at all. The last two tests
 * here are the ones that hold that in place; the rest pin the answers that must NOT move (B3).
 */
class BookRootTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired ScopeResolver scopeResolver;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TableQueryExecutor queryExecutor;

    User admin;
    User sam;
    User sid;
    User colin;
    Customer acme;
    Product widget;
    Invoice samsInvoice;
    Invoice sidsInvoice;
    Invoice orphanInvoice;
    Payment colinsPayment;
    Payment orphanPayment;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        colin = user("colin.collect", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
        samsInvoice = invoice(sam);
        sidsInvoice = invoice(sid);
        orphanInvoice = unassigned(invoice(sam));
        colinsPayment = payment(colin);
        orphanPayment = unassigned(payment(colin));
    }

    /**
     * The row the empty filter exists for: work nobody owns. This is the answer B3 declared it was
     * changing; it is in fact the answer that already held, and it is pinned here so that it goes
     * on holding whichever way the path is spelled (B3).
     */
    @Test
    void anInvoiceWithNoSalesPocIsFoundByTheEmptySalesPocFilter() throws Exception {
        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:isEmpty:").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orphanInvoice.getId()));

        // And the complement still answers the other two, so nothing was widened by accident.
        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:isNotEmpty:").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** The same question on payments: nobody is chasing this money (B3). */
    @Test
    void aPaymentWithNoCollectionPocIsFoundByTheEmptyCollectionPocFilter() throws Exception {
        mockMvc.perform(get("/api/payments?filter=collectionPocUserId:isEmpty:").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orphanPayment.getId()));

        mockMvc.perform(get("/api/payments?filter=collectionPocUserId:isNotEmpty:").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(colinsPayment.getId()));
    }

    /**
     * THE NON-CHANGE THAT MATTERS MOST: re-spelling the book must not move one row into or out of
     * anybody's book. A Sales POC sees their own invoice and neither of the other two — not the
     * other salesperson's, and NOT the unassigned one, which a null-tolerant comparison would
     * start matching the day somebody wrote the flat test as {@code neq} instead of {@code eq}
     * (B3).
     */
    @Test
    void aSalesPocStillSeesExactlyTheirOwnInvoicesAndNobodyElses() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(samsInvoice.getId()));

        mockMvc.perform(get("/api/invoices").with(as(sid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sidsInvoice.getId()));

        // The tile shares the predicate list with the list, so it has to agree with it.
        mockMvc.perform(get("/api/invoices/summary").with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        // A single-record read is the same book, answered 404 and never 403 (AUTH-08).
        mockMvc.perform(get("/api/invoices/" + sidsInvoice.getId()).with(as(sam)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/" + orphanInvoice.getId()).with(as(sam)))
                .andExpect(status().isNotFound());
    }

    /** The column the chip names is still filterable, and still selects the same rows (B3). */
    @Test
    void filteringBySalesPocUserIdEqualsMatchesTheSameRowsItDidBefore() throws Exception {
        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:eq:" + sam.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(samsInvoice.getId()));

        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:in:"
                        + sam.getId() + "," + sid.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // The chip the book itself writes IS this filter, so applying it on top of the book must
        // be a no-op rather than a second, differently-spelled restriction.
        mockMvc.perform(get("/api/invoices?filter=salesPocUserId:eq:" + sam.getId()).with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(samsInvoice.getId()));
    }

    /** The same non-change on the other two books, whose POC is a collection seat (B3). */
    @Test
    void filteringPaymentsAndPromisesByCollectionPocUserIdMatchesTheSameRowsItDidBefore()
            throws Exception {
        PromiseDtos.PromiseDto promise = promise(colin);

        mockMvc.perform(get("/api/payments?filter=collectionPocUserId:eq:" + colin.getId())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(colinsPayment.getId()));

        mockMvc.perform(get("/api/promises?filter=collectionPocUserId:eq:" + colin.getId())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(promise.id()));

        // payment_promises.collection_poc_user_id is NOT NULL, so the empty filter is vacuous
        // there and answers nothing both before and after this unit — recorded rather than
        // assumed, because it is the one of the three columns nothing could change (B3).
        mockMvc.perform(get("/api/promises?filter=collectionPocUserId:isEmpty:").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * The book is read off the foreign key the invoice already carries. On Hibernate 6.5 this was
     * ALREADY true of the association-id spelling, so this test passed before the change and is a
     * tripwire rather than a proof: it catches a future edit that reaches the user table for real
     * (say {@code root.get("salesPoc").get("username")}), and an ORM upgrade that stops folding
     * the association-id path onto the foreign key. What it cannot catch is a plain revert to
     * {@code root.get("salesPoc").get("id")} — for that, see the last test in this class (B3).
     *
     * <p>Read on {@code idsMatching}, which is the same funnel the list uses minus the fetch joins
     * that populate the DTO: those join users on purpose, to render the POC's name.
     */
    @Test
    void theBookQueryNoLongerJoinsTheUserTable() throws Exception {
        actAs(sam);
        List<String> sql = CountingStatements.capture(() ->
                assertThat(invoiceService.idsMatching(
                        TableQuery.parse(TableSchemas.INVOICES, 0, 20, null, List.of()), 100))
                        .containsExactly(samsInvoice.getId()));

        List<String> bookQueries = sql.stream()
                .filter(s -> s.startsWith("select"))
                .filter(s -> s.contains("from invoices"))
                .toList();
        assertThat(bookQueries).as("the book query ran at all").isNotEmpty();
        assertThat(bookQueries)
                .as("the book is read off invoices.sales_poc_user_id, with no join to users")
                .allSatisfy(s -> assertThat(s).doesNotContain("users"));
        assertThat(bookQueries)
                .as("and it still restricts to the caller")
                .allSatisfy(s -> assertThat(s).contains("sales_poc_user_id"));
    }

    /**
     * Both chips survive the re-spelling. The book chip is written by ScopeResolver and the region
     * chip by RegionScope, and the invariant B1 protects — no region predicate ever enters
     * Scope.predicates() or Scope.lockedFilters() — is what keeps them two separate lists that
     * PageResponse.of concatenates, book first (B3, B1).
     */
    @Test
    void theRegionChipAndTheBookChipAreBothStillOnThePage() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFilters[0]").value("salesPocUserId:eq:" + sam.getId()))
                .andExpect(jsonPath("$.lockedFilters[1]")
                        .value("regionId:in:" + defaultRegion().getId()))
                .andExpect(jsonPath("$.lockedFilters.length()").value(2));

        // The administrator holds SCOPE_OVERRIDE and a wildcard grant, so neither list has
        // anything to say and the page carries no chip at all.
        mockMvc.perform(get("/api/invoices").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFilters").isEmpty());
    }

    /**
     * THE FIRST OF THE TWO LOAD-BEARING TESTS. The read-only duplicate mappings exist, they read
     * the SAME column the association writes, and they read null where the association is null.
     * Asked through a criteria predicate rather than a getter on purpose: an attribute that is not
     * mapped fails to resolve at RUNTIME here, so deleting the mapping turns this test red instead
     * of merely refusing to compile something later in the build (B3).
     */
    @Test
    void everyBookEntityCarriesItsForeignKeysAsPlainLongsThatAgreeWithItsAssociations() {
        PromiseDtos.PromiseDto promise = promise(colin);
        actAs(admin);

        assertThat(matches(Invoice.class, TableSchemas.INVOICES, samsInvoice.getId(),
                (root, q, cb) -> cb.equal(root.get("salesPocUserId"), sam.getId()))).isTrue();
        assertThat(matches(Invoice.class, TableSchemas.INVOICES, samsInvoice.getId(),
                (root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isTrue();
        // The association is null here, so the duplicate has to be null too — the one row that
        // would still read as "assigned" if the two mappings ever came apart.
        assertThat(matches(Invoice.class, TableSchemas.INVOICES, orphanInvoice.getId(),
                (root, q, cb) -> cb.isNull(root.get("salesPocUserId")))).isTrue();

        assertThat(matches(Payment.class, TableSchemas.PAYMENTS, colinsPayment.getId(),
                (root, q, cb) -> cb.equal(root.get("collectionPocUserId"), colin.getId()))).isTrue();
        assertThat(matches(Payment.class, TableSchemas.PAYMENTS, colinsPayment.getId(),
                (root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isTrue();
        assertThat(matches(Payment.class, TableSchemas.PAYMENTS, orphanPayment.getId(),
                (root, q, cb) -> cb.isNull(root.get("collectionPocUserId")))).isTrue();

        assertThat(matches(PaymentPromise.class, TableSchemas.PROMISES, promise.id(),
                (root, q, cb) -> cb.equal(root.get("collectionPocUserId"), colin.getId()))).isTrue();
        assertThat(matches(PaymentPromise.class, TableSchemas.PROMISES, promise.id(),
                (root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isTrue();
    }

    /**
     * THE SECOND, AND THE GUARD THE WHOLE OF B3 STANDS ON. Both the three ColumnDefs and the three
     * book predicates must NAME the flat foreign key, because that name is the only one a mirror
     * root can also answer to: InvoiceHistory maps sales_poc_user_id as a plain Long and has no
     * User association, so {@code root.get("salesPoc").get("id")} would throw against it. Whoever
     * edits ScopeResolver or TableSchemas next must not put the association walk back — this is
     * what will tell them.
     *
     * <p>It asks the criteria tree which attribute the expression names rather than reading the
     * SQL, because on Hibernate 6.5 the two spellings emit IDENTICAL SQL (measured) and no
     * black-box test can tell them apart. JPA's own API cannot reach inside a Predicate to its
     * operands, so the one Hibernate type this class knows about is used to get there (B3).
     */
    @Test
    void theThreePocColumnsAndTheThreeBookPredicatesNameTheFlatForeignKey() {
        assertThat(attributeBehind(TableSchemas.INVOICES, Invoice.class, "salesPocUserId"))
                .isEqualTo("salesPocUserId");
        assertThat(attributeBehind(TableSchemas.PAYMENTS, Payment.class, "collectionPocUserId"))
                .isEqualTo("collectionPocUserId");
        assertThat(attributeBehind(TableSchemas.PROMISES, PaymentPromise.class, "collectionPocUserId"))
                .isEqualTo("collectionPocUserId");

        // The name column is deliberately NOT changed: a full name genuinely lives on the other
        // table and its LEFT join is what keeps an unassigned row on the page.
        assertThat(attributeBehind(TableSchemas.INVOICES, Invoice.class, "salesPocName"))
                .isEqualTo("fullName");

        User bookie = user("bea.book", bookRole().getName());
        actAs(bookie);
        assertThat(bookPredicateAttribute(scopeResolver.forInvoices(), Invoice.class))
                .isEqualTo("salesPocUserId");
        assertThat(bookPredicateAttribute(scopeResolver.forPayments(), Payment.class))
                .isEqualTo("collectionPocUserId");
        assertThat(bookPredicateAttribute(scopeResolver.forPromises(), PaymentPromise.class))
                .isEqualTo("collectionPocUserId");
    }

    /** Does this one row satisfy that predicate? Everything else is left to the query funnel. */
    private <T> boolean matches(Class<T> type, TableSchema schema, Long id, PredicateFactory p) {
        TableQuery one = TableQuery.parseUnpaged(schema, null, List.of("id:eq:" + id));
        return queryExecutor.count(type, schema, one, List.of(p)) == 1;
    }

    /** The attribute a schema column's path resolves to, against a root of that entity. */
    private String attributeBehind(TableSchema schema, Class<?> type, String column) {
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        CriteriaQuery<Object> cq = cb.createQuery();
        Root<?> root = cq.from(type);
        Expression<?> path = schema.require(column).path().resolve(root, cq, cb);
        return named(path);
    }

    /** The attribute a book scope's single predicate compares against. */
    private String bookPredicateAttribute(ScopeResolver.Scope scope, Class<?> type) {
        assertThat(scope.predicates()).as("the caller really has a book").hasSize(1);
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        CriteriaQuery<Object> cq = cb.createQuery();
        Root<?> root = cq.from(type);
        Predicate p = scope.predicates().get(0).build(root, cq, cb);
        return named(((SqmComparisonPredicate) p).getLeftHandExpression());
    }

    private static String named(Expression<?> path) {
        return ((SqmPath<?>) path).getNavigablePath().getLocalName();
    }

    /**
     * A reader whose book really is a book: no SCOPE_OVERRIDE, assignable as both a sales and a
     * collection POC, so all three of forInvoices/forPayments/forPromises take their book arm.
     */
    private Role bookRole() {
        return roleRepository.findByName("BOOK_ONLY_BOTH_POC").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("BOOK_ONLY_BOTH_POC")
                        .description("Owns invoices and collects, in their own book only")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                                        Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW,
                                        Privileges.POC_ASSIGNABLE_SALES,
                                        Privileges.POC_ASSIGNABLE_COLLECTION)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
    }

    private Invoice invoice(User salesPoc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                null, null, salesPoc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    private Payment payment(User collectionPoc) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "CASH", null, List.of(),
                collectionPoc.getId(), null));
    }

    private PromiseDtos.PromiseDto promise(User collectionPoc) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                collectionPoc.getId(), null, null));
    }

    /**
     * A record with nobody on it, written through the repository because the create path refuses
     * one: PocService.requireAssignable answers "Sales POC is required" for a null id. The STATE
     * is nonetheless a first-class one — sales_poc_user_id is nullable, InvoiceService.update
     * reads {@code getSalesPoc() == null} on the line above the reassignment, and both the
     * pocMissing flag and the pocMissingCount tile exist to count exactly these rows — so it is
     * reached the way an operator reaches it, by taking the person off afterwards (B3).
     */
    private Invoice unassigned(Invoice inv) {
        inv.setSalesPoc(null);
        return invoiceRepository.save(inv);
    }

    private Payment unassigned(Payment payment) {
        payment.setCollectionPoc(null);
        return paymentRepository.save(payment);
    }
}
