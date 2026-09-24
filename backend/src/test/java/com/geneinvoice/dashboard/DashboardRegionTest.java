package com.geneinvoice.dashboard;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationRepository;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five dashboard figures are the one reader in the application that does not go through
 * TableQueryExecutor, so they are the one place the region axis has to be added by hand — and the
 * one place it can be forgotten. Between R4 and this unit every figure counted every branch in the
 * company while every list counted only the caller's own; these tests are what say that is closed,
 * and that closing it did not disturb the book (B1).
 */
class DashboardRegionTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAllocationRepository allocationRepository;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired ScopeResolver scopeResolver;
    @Autowired RegionScope regionScope;
    @Autowired DashboardService dashboardService;

    // Everything is dated into the current month so a one-month window holds all of it and the
    // assertions do not drift with the wall clock.
    static final Instant RAISED = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant();

    User admin;
    User cashier;
    User sales;
    User collections;
    User book;
    Region north;
    Customer home;
    Customer away;
    Invoice homeInvoice;
    Invoice awayInvoice;
    Payment homePayment;
    Payment awayPayment;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cashier = user("cara.cashier", "CASHIER");
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        book = user("bea.book", bookRole().getName());
        // A branch the two readers whose narrowing these tests assert — the cashier and bea — are
        // unstaffed in, and the administrator holds everywhere.
        north = region("NORTH");
        widget = product("Widget", "100.00");
        home = customer("Home Ltd");
        away = customerRepository.save(Customer.builder().name("Away Ltd").region(north).build());

        // A POC works where the records they own are: from R8 a per-record POC field is refused
        // for somebody who cannot MANAGE that branch, so the two people who own the NORTH records
        // hold NORTH, and bea owns her HQ book at MANAGE rather than merely looking at it. MANAGE
        // covers VIEW, so what every one of them can SEE is exactly what it was (B1, R8).
        staffedAt(book, defaultRegion());
        staffedAt(sales, north);
        staffedAt(collections, north);

        actAs(admin);
        homeInvoice = invoice(home, book);
        awayInvoice = invoice(away, sales);
        homePayment = payment(home, "40.00", book);
        awayPayment = payment(away, "60.00", collections);
    }

    /**
     * A reader whose book really is a book: no SCOPE_OVERRIDE, assignable as both a sales and a
     * collection POC, so collected() takes its PaymentAllocation branch and both membership arms
     * are live. That is the only shape in which the two explicit join predicates can be tested.
     */
    private Role bookRole() {
        return roleRepository.findByName("REGION_BOOK_POC").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("REGION_BOOK_POC")
                        .description("Owns invoices and collects payments, in their own book only")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                                        Privileges.PAYMENT_VIEW, Privileges.POC_ASSIGNABLE_SALES,
                                        Privileges.POC_ASSIGNABLE_COLLECTION)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
    }

    /** One more branch this person may work in, on top of whatever their role already implies. */
    private void staffedAt(User u, Region where) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId()).right(RegionRight.MANAGE).build());
    }

    private Invoice invoice(Customer c, User salesPoc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), RAISED, null,
                null, null, salesPoc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    private Payment payment(Customer c, String amount, User collectionPoc) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(amount), "Cash", null, List.of(), collectionPoc.getId(), null));
    }

    // -----------------------------------------------------------------------------------------

    @Test
    void eachDashboardFigureCountsOnlyTheRegionsTheCallerCanSee() throws Exception {
        // The administrator holds the wildcard and counts the whole company, exactly as before B1.
        assertThat(months(billed(admin, ""))).isEqualByComparingTo("200.00");
        assertThat(billed(admin, "").months().get(0).count()).isEqualTo(2);
        assertThat(outstanding(admin, "")).isEqualByComparingTo("100.00");
        assertThat(names(topOutstanding(admin, ""))).containsExactlyInAnyOrder("Home Ltd", "Away Ltd");
        assertThat(months(collected(admin, ""))).isEqualByComparingTo("100.00");
        assertThat(payingNames(topPaying(admin, ""))).containsExactlyInAnyOrder("Home Ltd", "Away Ltd");

        // The cashier works in one branch. Every figure now agrees with the lists, which have
        // counted only this branch since R4.
        assertThat(months(billed(cashier, ""))).isEqualByComparingTo("100.00");
        assertThat(billed(cashier, "").months().get(0).count()).isEqualTo(1);
        assertThat(outstanding(cashier, "")).isEqualByComparingTo("60.00");
        assertThat(names(topOutstanding(cashier, ""))).containsExactly("Home Ltd");
        assertThat(months(collected(cashier, ""))).isEqualByComparingTo("40.00");
        assertThat(payingNames(topPaying(cashier, ""))).containsExactly("Home Ltd");
    }

    @Test
    void collectedByMonthStillUsesThePaymentRootForACallerWithNoBook() throws Exception {
        actAs(cashier);
        // THE TRAP, READ DIRECTLY: the cashier holds SCOPE_OVERRIDE, so the book contributes no
        // chip — while the region axis narrows them to one branch. A region chip in
        // Scope.lockedFilters() would make paidAgainstBookOnly() true here and silently swap the
        // query root from Payment to PaymentAllocation for every caller in the company, which is
        // why region never enters ScopeResolver (B1).
        assertThat(scopeResolver.forInvoices().lockedFilters()).isEmpty();
        assertThat(regionScope.lockedFilters(Invoice.class))
                .containsExactly("regionId:in:" + defaultRegion().getId());

        LocalDate today = InvoiceDates.today();
        long allocationReads = CountingStatements.reads("payment_allocations",
                () -> dashboardService.collectedByMonth(1, today, null));
        assertThat(allocationReads).isZero();

        // And the figure itself is the payment-root one: the payment total, not an allocated sum.
        assertThat(months(collected(cashier, ""))).isEqualByComparingTo("40.00");
    }

    @Test
    void topPayingCustomersNeverNamesACustomerFromARegionTheCallerCannotSee() throws Exception {
        // Two allocations PaymentService.applyTo would never write — it only ever allocates to the
        // payment's own customer's invoices. The figure's region bound must not rest on that
        // invariant three layers away, so it is removed here and the figure must still be right.
        crossAllocate(homePayment, awayInvoice, "7.00");
        crossAllocate(awayPayment, homeInvoice, "9.00");

        actAs(book);
        // Not vacuous: this caller really is on the PaymentAllocation branch, where the outer root
        // has no region of its own and membership is an OR of two subqueries.
        assertThat(scopeResolver.forInvoices().lockedFilters()).isNotEmpty();
        LocalDate today = InvoiceDates.today();
        assertThat(CountingStatements.reads("payment_allocations",
                () -> dashboardService.topPaying(1, 5, today, null))).isEqualTo(1);

        // The allocation admitted by the PAYMENT arm points at an invoice in NORTH: without the
        // explicit predicate on the invoice join it would put "Away Ltd" on a page belonging to
        // somebody who cannot see NORTH at all.
        assertThat(payingNames(topPaying(book, ""))).containsExactly("Home Ltd");
        // And the one admitted by the INVOICE arm was paid by a payment in NORTH: without the
        // explicit predicate on the payment join its 9.00 would be counted as this branch's money.
        assertThat(topPaying(book, "").customers().get(0).collected()).isEqualByComparingTo("40.00");
        assertThat(months(collected(book, ""))).isEqualByComparingTo("40.00");
    }

    @Test
    void eachDashboardFigureSaysWhichRegionsItCovers() throws Exception {
        // A wildcard holder is not narrowed by anything, and the payload says so rather than
        // listing every branch in the company.
        assertThat(billed(admin, "").regionCoverage().allRegions()).isTrue();
        assertThat(billed(admin, "").regionCoverage().regions()).isEmpty();
        assertThat(outstandingByAge(admin, "").regionCoverage().allRegions()).isTrue();
        assertThat(topOutstanding(admin, "").regionCoverage().allRegions()).isTrue();
        assertThat(collected(admin, "").regionCoverage().allRegions()).isTrue();
        assertThat(topPaying(admin, "").regionCoverage().allRegions()).isTrue();

        for (DashboardDtos.RegionCoverage c : List.of(
                billed(cashier, "").regionCoverage(),
                outstandingByAge(cashier, "").regionCoverage(),
                topOutstanding(cashier, "").regionCoverage(),
                collected(cashier, "").regionCoverage(),
                topPaying(cashier, "").regionCoverage())) {
            assertThat(c.allRegions()).isFalse();
            assertThat(c.regions()).extracting(DashboardDtos.RegionRef::id)
                    .containsExactly(defaultRegion().getId());
            assertThat(c.regions()).extracting(DashboardDtos.RegionRef::code)
                    .containsExactly(regionProperties.defaultCode());
        }

        // The other end of the scale: somebody who can see no branch is TOLD so, which is what
        // explains a page of zeros, and is the dashboard's version of the isEmpty chip on a list.
        User stranger = user("stan.stranger", "CASHIER");
        revokeRegionGrants(stranger);
        DashboardDtos.MonthlySeries theirs = billed(stranger, "");
        assertThat(theirs.regionCoverage().allRegions()).isFalse();
        assertThat(theirs.regionCoverage().regions()).isEmpty();
        assertThat(months(theirs)).isEqualByComparingTo("0.00");
        assertThat(names(topOutstanding(stranger, ""))).isEmpty();

        // And a customer login is pinned to its own account rather than to a branch, so no region
        // narrows it and it is not told which branches exist — the same answer RegionScope gives a
        // list when it gives it no chip at all. Its coverage is still OWN, unchanged by B1.
        User login = customerUser("home.login", home.getId());
        DashboardDtos.MonthlySeries own = billed(login, "");
        assertThat(own.coverage()).isEqualTo(DashboardDtos.Coverage.OWN);
        assertThat(own.regionCoverage().allRegions()).isTrue();
        assertThat(own.regionCoverage().regions()).isEmpty();
        assertThat(months(own)).isEqualByComparingTo("100.00");
    }

    @Test
    void namingARegionNarrowsTheFiguresAndNamingOneYouCannotSeeReturnsZeroRatherThanForbidding()
            throws Exception {
        String toNorth = "&region=" + north.getId();

        // The administrator narrows to one branch and gets that branch's figures.
        assertThat(months(billed(admin, toNorth))).isEqualByComparingTo("100.00");
        assertThat(names(topOutstanding(admin, toNorth))).containsExactly("Away Ltd");
        assertThat(months(collected(admin, toNorth))).isEqualByComparingTo("60.00");
        assertThat(payingNames(topPaying(admin, toNorth))).containsExactly("Away Ltd");
        assertThat(billed(admin, toNorth).regionCoverage().allRegions()).isFalse();
        assertThat(billed(admin, toNorth).regionCoverage().regions())
                .extracting(DashboardDtos.RegionRef::code).containsExactly("NORTH");

        // The cashier asks for a branch they hold nothing in: 200 with zero, never a 403, because
        // no id space is being probed and a region you cannot see reads exactly like one that does
        // not exist (AUTH-08). A user-supplied narrowing can only ever narrow.
        assertThat(months(billed(cashier, toNorth))).isEqualByComparingTo("0.00");
        assertThat(names(topOutstanding(cashier, toNorth))).isEmpty();
        assertThat(months(collected(cashier, toNorth))).isEqualByComparingTo("0.00");
        assertThat(billed(cashier, toNorth).regionCoverage().allRegions()).isFalse();
        assertThat(billed(cashier, toNorth).regionCoverage().regions()).isEmpty();

        // A region that does not exist at all reads the same way, for the caller who can see
        // every region there is.
        assertThat(months(billed(admin, "&region=999999"))).isEqualByComparingTo("0.00");
        assertThat(billed(admin, "&region=999999").regionCoverage().regions()).isEmpty();

        // And the case the selector exists for: somebody who works in two branches narrowing to
        // one of them. The coverage names the branch they asked for, not both they hold.
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(cashier.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());
        assertThat(months(billed(cashier, ""))).isEqualByComparingTo("200.00");
        assertThat(months(billed(cashier, toNorth))).isEqualByComparingTo("100.00");
        assertThat(names(topOutstanding(cashier, toNorth))).containsExactly("Away Ltd");
        assertThat(billed(cashier, toNorth).regionCoverage().regions())
                .extracting(DashboardDtos.RegionRef::code).containsExactly("NORTH");
    }

    @Test
    void theRegionPredicateDoesNotMultiplyTheQueryCountPerFigure() throws Exception {
        actAs(cashier);
        LocalDate today = InvoiceDates.today();
        // One select per figure, as before B1: the axis is a join and an in-list, not a query per
        // branch. CountingStatements reads the raw SQL, so this is a direct reading of what was
        // sent to the database.
        assertThat(CountingStatements.reads("invoices",
                () -> dashboardService.billedByMonth(1, today, null))).isEqualTo(1);
        assertThat(CountingStatements.reads("invoices",
                () -> dashboardService.outstandingByAge(today, null))).isEqualTo(1);
        assertThat(CountingStatements.reads("invoices",
                () -> dashboardService.topOutstanding(5, null))).isEqualTo(1);
        assertThat(CountingStatements.reads("payments",
                () -> dashboardService.collectedByMonth(1, today, null))).isEqualTo(1);
        assertThat(CountingStatements.reads("payments",
                () -> dashboardService.topPaying(1, 5, today, null))).isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------

    private void crossAllocate(Payment payment, Invoice invoice, String amount) {
        allocationRepository.save(PaymentAllocation.builder()
                .payment(payment).invoice(invoice).amount(new BigDecimal(amount)).build());
    }

    private DashboardDtos.MonthlySeries billed(User u, String extra) throws Exception {
        return read("/api/dashboard/billed-by-month?months=1" + extra, u,
                DashboardDtos.MonthlySeries.class);
    }

    private DashboardDtos.MonthlySeries collected(User u, String extra) throws Exception {
        return read("/api/dashboard/collected-by-month?months=1" + extra, u,
                DashboardDtos.MonthlySeries.class);
    }

    private DashboardDtos.OutstandingByAge outstandingByAge(User u, String extra) throws Exception {
        // The one figure with no parameters of its own, so the narrowing is the whole query.
        String query = extra.isEmpty() ? "" : "?" + extra.substring(1);
        return read("/api/dashboard/outstanding-by-age" + query, u,
                DashboardDtos.OutstandingByAge.class);
    }

    private DashboardDtos.TopOutstanding topOutstanding(User u, String extra) throws Exception {
        return read("/api/dashboard/top-outstanding-customers?limit=5" + extra, u,
                DashboardDtos.TopOutstanding.class);
    }

    private DashboardDtos.TopPaying topPaying(User u, String extra) throws Exception {
        return read("/api/dashboard/top-paying-customers?months=1&limit=5" + extra, u,
                DashboardDtos.TopPaying.class);
    }

    private <T> T read(String url, User u, Class<T> type) throws Exception {
        String body = mockMvc.perform(get(url).with(as(u)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, type);
    }

    private static BigDecimal months(DashboardDtos.MonthlySeries series) {
        return series.months().stream().map(DashboardDtos.MonthPoint::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal outstanding(User u, String extra) throws Exception {
        return outstandingByAge(u, extra).buckets().stream().map(DashboardDtos.AgeBucket::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> names(DashboardDtos.TopOutstanding top) {
        return top.customers().stream().map(DashboardDtos.OutstandingCustomer::customerName).toList();
    }

    private static List<String> payingNames(DashboardDtos.TopPaying top) {
        return top.customers().stream().map(DashboardDtos.PayingCustomer::customerName).toList();
    }
}
