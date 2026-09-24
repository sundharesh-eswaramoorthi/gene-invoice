package com.geneinvoice.asof;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfEndpoints;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dashboard.DashboardDtos;
import com.geneinvoice.history.FixedHistoryClock;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationRepository;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE FIVE DASHBOARD FIGURES, ASKED AS OF A DATE — and the first safety net that package has ever
 * had (B3).
 *
 * <p>WHY THIS PACKAGE NEEDED ITS OWN UNIT AND ITS OWN TESTS. Every other read in the application
 * goes through {@code TableQueryExecutor}, so B3-SLICE-INVOICE's ten-line source() switch made the
 * invoice list, its tiles, its detail and its export as-of at once. {@code DashboardService}
 * bypasses the executor entirely and applies the book through a {@code scoped()} of its own, which
 * is why it needed separate region work in R6 and separate as-of work here: three invoice roots
 * swapped by hand, a second body for the two money figures, and an interval clause that has to be
 * ANDed onto every mirror root by this class rather than by the executor.
 *
 * <p>TWO OF THE FIVE FIGURES NOW HAVE TWO CODE PATHS THAT CAN DRIFT APART — {@code collected()} and
 * its sibling {@code collectedAsOf()} — and that is the risk this unit carries. The net under it is
 * {@link #everyDashboardFigureAsOfTodayEqualsTheLiveFigure}, which drives BOTH branches (a wildcard
 * reader on the payment root, a POC on the allocation root) and compares the two answers field for
 * field through Jackson. It is the test that catches a transcription error in this unit at large,
 * and it is honest about what it cannot catch: both paths wrong in the same way.
 *
 * <p>"AS OF TODAY" HAS TO BE OPENED BY HAND, AND THE REASON IS NOT A CONVENIENCE. {@code
 * AsOfDates.parse} short-circuits any date that is not in the past to the live path, so
 * {@code ?asOf=<today>} over HTTP is literally the live code answering twice and could never
 * compare the MIRROR against the live table — B3-SLICE-INVOICE named that gap and it is the gap
 * these two tests are written to close. {@code AsOfInterceptor} does not touch the context on a
 * request that carries no {@code asOf} parameter, so a context opened on the test thread survives
 * into the handler and the whole HTTP path answers off the mirror. Both claims are asserted: the
 * wire one ({@code ?asOf=<today>} equals live) and the real one (the mirror at today equals live).
 *
 * <p>THE TIMELINES ARE REAL. Every version read here was written by the production history writer
 * from a real create, payment, edit or void, with {@link FixedHistoryClock} holding the write-side
 * clock at the day in question. Nothing in this class inserts a mirror row by hand.
 * {@link TestHistoryFloor} pushes the floor back to 2000 so the answers are the ordinary
 * RECONSTRUCTED ones; {@link #aDashboardFigureSaysWhichDateItWasAskedAsOf} moves it forward again
 * to cover the pre-floor branch.
 */
@Import({FixedHistoryClock.Config.class, TestHistoryFloor.Config.class})
class AsOfFiguresTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAllocationRepository allocationRepository;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired ScopeResolver scopeResolver;
    @Autowired FixedHistoryClock clock;
    @Autowired TestHistoryFloor floor;

    /** The five, with the parameters the shipped client sends. */
    private static final List<String> FIGURES = List.of(
            "/api/dashboard/billed-by-month?months=12",
            "/api/dashboard/outstanding-by-age",
            "/api/dashboard/top-outstanding-customers?limit=5",
            "/api/dashboard/collected-by-month?months=12",
            "/api/dashboard/top-paying-customers?months=12&limit=5");

    /** The ageing buckets, by position, so the en-dashes in their labels are never retyped. */
    private static final int NOT_YET_DUE = 0;
    private static final int DAYS_1_30 = 1;
    private static final int DAYS_31_60 = 2;

    User admin;
    Customer acme;
    Product widget;
    LocalDate today;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd");
        today = LocalDate.now(ZoneOffset.UTC);
        actAs(admin);
    }

    /**
     * The clock, the floor and the thread's own date are all singletons or thread state in a
     * cached context: a test that moved one and did not put it back would date every later test's
     * mirror rows at its own instant, floor every later answer at its own day, or answer the next
     * test's fixture from the past (B3).
     */
    @AfterEach
    void putEverythingBack() {
        clock.release();
        floor.reset();
        AsOfContext.clear();
    }

    // ------------------------------------------------------------------ the net under the unit

    /**
     * THE SAFETY NET, AND THE FIRST TEST {@code DashboardService} HAS EVER HAD. The same question
     * asked of the live tables and of the mirrors at the same moment must come back identical,
     * field for field, for every one of the five figures and for BOTH readers — a wildcard holder,
     * whose money figures root on {@code payment_history}, and a POC whose book makes them root on
     * {@code payment_allocation_history} joined to the invoice and payment mirrors.
     *
     * <p>It is not vacuous and it is not the {@code ?asOf=<today>} short circuit: the context is
     * opened on the test thread, so the request really does read the mirror (see the class
     * Javadoc). The fixture contains an invoice with TWO versions on purpose — drop
     * {@code AsOf.at(T)} from {@code DashboardService.scoped} and that invoice is counted twice
     * and this test is the one that says so.
     */
    @Test
    void everyDashboardFigureAsOfTodayEqualsTheLiveFigure() throws Exception {
        User poc = bookUser("pat.poc");
        Customer beta = customer("Beta Ltd");
        placeForever(acme);
        placeForever(beta);

        Invoice big = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", poc);
        raise(beta, today.minusDays(60), today.minusDays(5), "500.00", poc);
        // A second version of one record, so a missing interval clause double-counts it.
        edit(big, "amended", today.minusDays(35));
        pay(acme, big, "400.00", today.minusDays(20), poc);

        for (User who : List.of(admin, poc)) {
            for (String url : FIGURES) {
                assertThat(asOfTree(url, today, who))
                        .describedAs("%s read off the mirror as of today, for %s", url,
                                who.getUsername())
                        .isEqualTo(liveTree(url, who));
            }
        }
        // Not vacuous: the POC really is on the allocation branch, which is the second body.
        actAs(poc);
        assertThat(scopeResolver.forInvoices().lockedFilters()).isNotEmpty();
        // And there really was money and an outstanding balance to compare, in both readers' books.
        assertThat(liveTree(FIGURES.get(2), admin).get("customers").size()).isPositive();
        assertThat(liveTree(FIGURES.get(4), admin).get("customers").size()).isPositive();
    }

    /**
     * The same net across the {@code /summary} tiles, and it is derived from the allowlist rather
     * than from a list typed here: every GET in {@link AsOfEndpoints#AS_OF_CAPABLE} whose pattern
     * ends in {@code /summary} is compared, so a tile allowlisted by a later slice unit joins this
     * test with no edit and cannot quietly ship without one.
     *
     * <p>Both halves of the claim are asserted — the wire one, {@code ?asOf=<today>} equals live
     * (which is the short circuit and therefore cannot fail), and the real one, the MIRROR at
     * today equals live.
     */
    @Test
    void everyTileAsOfTodayEqualsTheLiveTile() throws Exception {
        Invoice big = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        edit(big, "amended", today.minusDays(35));
        pay(acme, big, "400.00", today.minusDays(20), admin);
        raise(acme, today.minusDays(3), today.plusDays(20), "700.00", admin);

        List<String> tiles = AsOfEndpoints.AS_OF_CAPABLE.stream()
                .filter(key -> key.startsWith("GET ") && key.endsWith("/summary"))
                .map(key -> key.substring("GET ".length()))
                .filter(pattern -> !pattern.contains("{"))
                .sorted()
                .toList();
        assertThat(tiles).describedAs("no tile is allowlisted, so this test proves nothing")
                .isNotEmpty();

        for (String url : tiles) {
            JsonNode live = liveTree(url, admin);
            assertThat(asOfTree(url, today, admin))
                    .describedAs("%s read off the mirror as of today", url)
                    .isEqualTo(live);
            assertThat(tree(body(get(url).param("asOf", today.toString()).with(as(admin)))))
                    .describedAs("%s?asOf=<today> on the wire", url)
                    .isEqualTo(live);
        }
    }

    // ------------------------------------------------------------------ the five figures

    /**
     * The ageing buckets are computed from {@code DashboardController.today()}, which follows the
     * reader into the past — so an invoice thirty days overdue TODAY was ten days overdue thirty
     * days ago and belongs in a different bucket, for a different amount, because nothing had been
     * paid on it yet. The window each bucket reports is asserted too: a client rendering
     * "due between X and Y" must be shown the past's window and not today's (B3, D4).
     */
    @Test
    void outstandingByAgeAsOfAPastDateAgesAgainstThatDate() throws Exception {
        LocalDate asked = today.minusDays(30);
        Invoice inv = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        pay(acme, inv, "400.00", today.minusDays(20), admin);

        DashboardDtos.OutstandingByAge past = asOf(FIGURES.get(1), asked, admin,
                DashboardDtos.OutstandingByAge.class);
        assertThat(past.buckets().get(DAYS_1_30).amount()).isEqualByComparingTo("1000.00");
        assertThat(past.buckets().get(DAYS_1_30).count()).isEqualTo(1);
        assertThat(past.buckets().get(DAYS_31_60).amount()).isEqualByComparingTo("0.00");
        assertThat(past.buckets().get(DAYS_1_30).dueDateTo()).isEqualTo(asked.minusDays(1));
        assertThat(past.buckets().get(DAYS_1_30).dueDateFrom()).isEqualTo(asked.minusDays(30));
        assertThat(past.buckets().get(NOT_YET_DUE).dueDateFrom()).isEqualTo(asked);

        DashboardDtos.OutstandingByAge live = live(FIGURES.get(1), admin,
                DashboardDtos.OutstandingByAge.class);
        assertThat(live.buckets().get(DAYS_31_60).amount()).isEqualByComparingTo("600.00");
        assertThat(live.buckets().get(DAYS_1_30).amount()).isEqualByComparingTo("0.00");
        assertThat(live.buckets().get(DAYS_31_60).dueDateTo()).isEqualTo(today.minusDays(31));
    }

    /**
     * The month window ends at the month being asked about, because {@code window(months, today)}
     * reads the same seam. Two things are proved at once and they are different things: money
     * raised in a LATER month is outside the window, and money raised in the SAME month but on a
     * later DAY is inside the window and still absent — because the record did not exist yet, and
     * that is the mirror root and the interval clause rather than the window (B3).
     */
    @Test
    void billedByMonthAsOfAPastDateEndsAtThatMonth() throws Exception {
        YearMonth askedMonth = YearMonth.from(today).minusMonths(2);
        YearMonth middleMonth = YearMonth.from(today).minusMonths(1);
        LocalDate asked = askedMonth.atDay(15);

        raise(acme, askedMonth.atDay(5), askedMonth.atDay(5).plusDays(60), "1000.00", admin);
        raise(acme, askedMonth.atDay(25), askedMonth.atDay(25).plusDays(60), "300.00", admin);
        raise(acme, middleMonth.atDay(5), middleMonth.atDay(5).plusDays(60), "700.00", admin);

        String url = "/api/dashboard/billed-by-month?months=3";
        DashboardDtos.MonthlySeries past = asOf(url, asked, admin, DashboardDtos.MonthlySeries.class);
        assertThat(past.months()).extracting(DashboardDtos.MonthPoint::month).containsExactly(
                askedMonth.minusMonths(2).toString(), askedMonth.minusMonths(1).toString(),
                askedMonth.toString());
        assertThat(total(past)).isEqualByComparingTo("1000.00");
        assertThat(past.months().get(2).count()).isEqualTo(1);

        DashboardDtos.MonthlySeries live = live(url, admin, DashboardDtos.MonthlySeries.class);
        assertThat(live.months()).extracting(DashboardDtos.MonthPoint::month).containsExactly(
                askedMonth.toString(), middleMonth.toString(), YearMonth.from(today).toString());
        assertThat(total(live)).isEqualByComparingTo("2000.00");
    }

    /**
     * Both halves of the figure move: the RANK, because the balances are that date's balances, and
     * the NAME, because a mirror row carries the account's name as a column of its own instead of
     * joining to the live account and rendering today's.
     *
     * <p>THE NAME IS THE NAME AS OF THAT ROW'S LAST CHANGE, not as of the date asked. Here they are
     * the same thing — the rename happened after the last invoice write — which is the ordinary
     * case; the case where they differ is named in this unit's honest gaps and B3-SCHEMAS flagged
     * it first (B3).
     */
    @Test
    void topOutstandingCustomersAsOfAPastDateRanksByThatDatesBalancesAndShowsThatDatesNames()
            throws Exception {
        Customer beta = customer("Beta Ltd");
        Invoice big = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        raise(beta, today.minusDays(60), today.minusDays(40), "500.00", admin);
        pay(acme, big, "900.00", today.minusDays(20), admin);
        rename(acme, "Acme Renamed Ltd", today.minusDays(5));

        DashboardDtos.TopOutstanding past = asOf(FIGURES.get(2), today.minusDays(30), admin,
                DashboardDtos.TopOutstanding.class);
        assertThat(past.customers()).extracting(DashboardDtos.OutstandingCustomer::customerName)
                .containsExactly("Acme Ltd", "Beta Ltd");
        assertThat(past.customers().get(0).outstanding()).isEqualByComparingTo("1000.00");
        assertThat(past.customers().get(0).openInvoices()).isEqualTo(1);

        DashboardDtos.TopOutstanding live = live(FIGURES.get(2), admin,
                DashboardDtos.TopOutstanding.class);
        assertThat(live.customers()).extracting(DashboardDtos.OutstandingCustomer::customerName)
                .containsExactly("Beta Ltd", "Acme Renamed Ltd");
        assertThat(live.customers().get(1).outstanding()).isEqualByComparingTo("100.00");
    }

    /**
     * The status a money figure reads is the status the payment HAD on the date asked about, and
     * the two directions are asserted in the order that proves it. A payment voided AFTER the date
     * asked was still on the book then, so the as-of figure counts it while the live one does not;
     * asked as of a date after the void it is gone from both.
     *
     * <p>The declared name reads as the second assertion; the first is the one with the teeth, and
     * it is asserted first for that reason (B3).
     */
    @Test
    void collectedByMonthAsOfAPastDateExcludesAPaymentVoidedAfterThatDate() throws Exception {
        Invoice inv = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        Payment paid = pay(acme, inv, "400.00", today.minusDays(40), admin);
        voidAt(paid, today.minusDays(20));

        assertThat(total(asOf(FIGURES.get(3), today.minusDays(30), admin,
                DashboardDtos.MonthlySeries.class))).isEqualByComparingTo("400.00");
        assertThat(total(asOf(FIGURES.get(3), today.minusDays(10), admin,
                DashboardDtos.MonthlySeries.class))).isEqualByComparingTo("0.00");
        assertThat(total(live(FIGURES.get(3), admin, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("0.00");

        // Not vacuous: the figure really did read the mirror and really did not read the live table.
        LocalDate asked = today.minusDays(30);
        List<String> sql = CountingStatements.capture(() -> asOf(FIGURES.get(3), asked, admin,
                DashboardDtos.MonthlySeries.class));
        assertThat(sql).anyMatch(s -> s.startsWith("select") && s.contains("payment_history"));
        assertThat(sql).noneMatch(s -> s.startsWith("select") && s.contains("from payments"));
    }

    /**
     * A reader whose book really is a book puts {@code collected()} on its allocation branch, which
     * live is a walk from the allocation to the payment, the invoice and the invoice's account.
     * As of a date it is the same figure over three mirrors held to one version each, and the
     * allocations it uses are the allocations THEN: voiding the payment clears them, so the live
     * figure is zero while the figure as of the day before the void is the money that really was on
     * the book that day (B3).
     */
    @Test
    void aBookScopedCollectedFigureAsOfAPastDateUsesTheAllocationsOfThatDate() throws Exception {
        User poc = bookUser("pat.poc");
        placeForever(acme);
        Invoice inv = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", poc);
        Payment paid = pay(acme, inv, "400.00", today.minusDays(40), poc);
        voidAt(paid, today.minusDays(20));

        actAs(poc);
        // Not vacuous: this caller is on the allocation branch and not the payment one.
        assertThat(scopeResolver.forInvoices().lockedFilters()).isNotEmpty();

        LocalDate asked = today.minusDays(30);
        assertThat(total(asOf(FIGURES.get(3), asked, poc, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("400.00");
        assertThat(total(live(FIGURES.get(3), poc, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("0.00");

        List<String> sql = CountingStatements.capture(() -> asOf(FIGURES.get(3), asked, poc,
                DashboardDtos.MonthlySeries.class));
        // ONE statement for the figure, not one per root and not one per row: the three roots
        // stand in for the live body's root and its two joins and cost the same single select.
        // Keyed on the two mirror tables together, because markDrift probes each mirror with a
        // cheap exists of its own and those name one table apiece (B3).
        assertThat(sql.stream().filter(s -> s.startsWith("select")
                && s.contains("payment_allocation_history") && s.contains("invoice_history"))
                .count()).isEqualTo(1);
        assertThat(sql).noneMatch(s -> s.startsWith("select")
                && s.contains("from payment_allocations"));
    }

    /**
     * THE DEVIATION THIS UNIT SHIPPED, PINNED. B3's text says {@code collectedAsOf} reads
     * {@code payment_allocation_history} flat with no joins at all, which assumes the denormalised
     * {@code customer_id} on that row is the INVOICE's account. It is not — HistoryRegistry's
     * projector fills it from the PAYMENT's account — so reading it flat would credit the payer
     * instead of the payee and would drop the invoice-side region bound the live body's own comment
     * insists on. The figure therefore reaches the invoice mirror by its id, exactly as the live
     * body reaches the invoice by its join, and this test is what fails if somebody "simplifies" it
     * back to the flat column (B3).
     */
    @Test
    void anAsOfCollectedFigureCreditsTheInvoicesAccountAndNotThePayers() throws Exception {
        User poc = bookUser("pat.poc");
        Customer beta = customer("Beta Ltd");
        placeForever(acme);
        placeForever(beta);
        Invoice betaInvoice = raise(beta, today.minusDays(60), today.minusDays(40), "1000.00", poc);
        // An allocation PaymentService.applyTo would never write — it only ever allocates to the
        // payment's own account's invoices — so that the two accounts on the row are different and
        // the figure has to say which one it means.
        Payment acmePayment = pay(acme, null, "400.00", today.minusDays(40), poc);
        crossAllocate(acmePayment, betaInvoice, "400.00", today.minusDays(40));

        LocalDate asked = today.minusDays(30);
        DashboardDtos.TopPaying past = asOf(FIGURES.get(4), asked, poc,
                DashboardDtos.TopPaying.class);
        assertThat(past.customers()).extracting(DashboardDtos.PayingCustomer::customerName)
                .containsExactly("Beta Ltd");
        assertThat(past.customers().get(0).collected()).isEqualByComparingTo("400.00");
        // And it is the same account the live figure credits, which is the whole claim.
        assertThat(live(FIGURES.get(4), poc, DashboardDtos.TopPaying.class).customers())
                .extracting(DashboardDtos.PayingCustomer::customerName).containsExactly("Beta Ltd");
    }

    /**
     * THE OTHER HALF OF THE SAME DEVIATION. On the allocation branch the outer row has no branch of
     * its own and membership is an OR of two subqueries, so the live body bounds BOTH ends
     * explicitly — an allocation admitted by the payment arm must not NAME an account from a branch
     * the caller cannot see, and one admitted by the invoice arm must not COUNT money from a
     * payment there. The as-of body says both on the two mirror roots that stand in for the two
     * joins, and this is the test that fails if either is dropped. It is DashboardRegionTest's
     * live scenario, asked as of a date (B1, B3).
     */
    @Test
    void anAsOfCollectedFigureNeverCountsMoneyFromABranchTheCallerCannotSee() throws Exception {
        Region north = region("NORTH");
        User poc = bookUser("pat.poc");
        Customer away = customerRepository.save(
                Customer.builder().name("Away Ltd").region(north).build());
        placeForever(acme);
        placeForever(away);

        Invoice mine = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", poc);
        Invoice theirs = raise(away, today.minusDays(60), today.minusDays(40), "500.00", admin);
        Payment myPayment = pay(acme, mine, "400.00", today.minusDays(50), poc);
        Payment theirPayment = pay(away, theirs, "500.00", today.minusDays(50), admin);
        // Two allocations PaymentService.applyTo would never write, so the OR really can cross a
        // branch and the two explicit bounds are the only thing stopping it.
        crossAllocate(myPayment, theirs, "70.00", today.minusDays(50));
        crossAllocate(theirPayment, mine, "90.00", today.minusDays(50));

        LocalDate asked = today.minusDays(30);
        DashboardDtos.TopPaying past = asOf(FIGURES.get(4), asked, poc,
                DashboardDtos.TopPaying.class);
        // Without the bound on the invoice end, "Away Ltd" appears on this page at 70.00; without
        // the bound on the payment end, "Acme Ltd" reads 490.00 instead of 400.00.
        assertThat(past.customers()).extracting(DashboardDtos.PayingCustomer::customerName)
                .containsExactly("Acme Ltd");
        assertThat(past.customers().get(0).collected()).isEqualByComparingTo("400.00");
        assertThat(total(asOf(FIGURES.get(3), asked, poc, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("400.00");
        // The same answer the live figure gives, which is what "does not change meaning" means.
        assertThat(total(live(FIGURES.get(3), poc, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("400.00");
    }

    // ------------------------------------------------------------------ the envelope

    /**
     * A number from the past has to say it is from the past. A dashboard has no PageResponse
     * envelope and no lockedFilters chip to say it with, so every one of the four payload records
     * carries AsOfInfo as its third component — and a live read carries null there, so a client
     * cannot mistake one for the other. The pre-floor branch is covered too: below the floor the
     * answer is SEEDED, not exact, and says why in a sentence (B3).
     */
    @Test
    void aDashboardFigureSaysWhichDateItWasAskedAsOf() throws Exception {
        raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        LocalDate asked = today.minusDays(30);

        for (String url : FIGURES) {
            // Read off the JSON rather than through one of the four record types, because all
            // four carry the stamp under the same key and this test is about the stamp.
            JsonNode info = objectMapper.readTree(asOfResponse(url, asked, admin)).get("asOf");
            assertThat(info != null && !info.isNull())
                    .describedAs("%s says nothing about the date it was asked", url).isTrue();
            assertThat(info.get("date").asText()).isEqualTo(asked.toString());
            assertThat(info.get("origin").asText()).isEqualTo(AsOfContext.ORIGIN_RECONSTRUCTED);
            assertThat(info.get("exact").asBoolean()).isTrue();
            assertThat(info.get("appliesTo").asText()).isEqualTo(AsOfContext.APPLIES_TO);
            assertThat(info.get("floor").asText()).isEqualTo(TestHistoryFloor.DEFAULT.toString());
            assertThat(info.get("omittedDeleted").asInt()).isZero();
            assertThat(info.get("notes").size()).isZero();

            JsonNode answeredLive = objectMapper.readTree(body(get(url).with(as(admin)))).get("asOf");
            assertThat(answeredLive == null || answeredLive.isNull())
                    .describedAs("%s answered live still claims a date", url).isTrue();
        }

        // Below the floor the figure is still answered, from the install-time seed rows, and says
        // out loud that its values are the floor's rather than the day's.
        floor.at(today.minusDays(5));
        JsonNode seeded = objectMapper.readTree(asOfResponse(FIGURES.get(0), asked, admin))
                .get("asOf");
        assertThat(seeded.get("origin").asText()).isEqualTo(AsOfContext.ORIGIN_SEEDED);
        assertThat(seeded.get("exact").asBoolean()).isFalse();
        assertThat(seeded.get("notes").size()).isPositive();
        assertThat(seeded.get("floor").asText()).isEqualTo(today.minusDays(5).toString());
    }

    /**
     * All five figures are on the allowlist, asserted here rather than only in the class that owns
     * the list, so this unit's five cannot be dropped by somebody editing a shared file. The other
     * side of the line is asserted too: a dashboard POST — there is none today, so an unallowlisted
     * GET stands in — is still refused, which is what stops this going vacuous.
     */
    @Test
    void theFiveFiguresAreTheOnlyDashboardEndpointsOnTheAllowlist() throws Exception {
        assertThat(AsOfEndpoints.AS_OF_CAPABLE).contains(
                "GET /api/dashboard/billed-by-month",
                "GET /api/dashboard/outstanding-by-age",
                "GET /api/dashboard/top-outstanding-customers",
                "GET /api/dashboard/collected-by-month",
                "GET /api/dashboard/top-paying-customers");
        assertThat(AsOfEndpoints.AS_OF_CAPABLE.stream().filter(k -> k.contains("/api/dashboard/")))
                .hasSize(5);
        mockMvc.perform(get("/api/dashboard/billed-by-month?asOf=" + today.minusDays(1))
                        .with(as(admin)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ region

    /**
     * THE ONE THING THAT WOULD OTHERWISE 500, AND R6 FLAGGED IT WHEN IT WROTE THE THROW.
     * {@code inRegions} refused every axis but VIA_CUSTOMER, and every mirror root is
     * VIA_CUSTOMER_ID — so the first {@code ?region=}-narrowed as-of request, and only that
     * request, would have been a 500. All five are asserted, because all five take the parameter
     * and each builds its own root (B1, B3).
     */
    @Test
    void aNarrowedAsOfFigureScopesTheMirrorRootByRegionInsteadOfThrowing() throws Exception {
        Region north = region("NORTH");
        Customer away = customerRepository.save(
                Customer.builder().name("Away Ltd").region(north).build());
        // BOTH ACCOUNTS ARE PLACED, because ?region= under an open as-of date now reads the
        // placement ledger — the same reading filter=regionId:eq: makes on the equivalent list,
        // where the two used to disagree for an account that had moved. A fixture account written
        // straight to the table has no ledger row and is therefore in no branch on any past date,
        // which is the landmine placeForever exists for (B1, B3).
        placeForever(acme);
        placeForever(away);
        LocalDate asked = today.minusDays(30);

        Invoice home = raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);
        Invoice there = raise(away, today.minusDays(60), today.minusDays(40), "500.00", admin);
        pay(acme, home, "100.00", today.minusDays(50), admin);
        pay(away, there, "300.00", today.minusDays(50), admin);

        String toNorth = "&region=" + north.getId();
        // Every figure answers rather than throwing, which is the headline.
        for (String url : FIGURES) {
            asOfResponse(url + (url.contains("?") ? toNorth : "?" + toNorth.substring(1)),
                    asked, admin);
        }

        // And the narrowing really narrows, on the mirror root, for every shape of figure.
        assertThat(total(asOf(FIGURES.get(0) + toNorth, asked, admin,
                DashboardDtos.MonthlySeries.class))).isEqualByComparingTo("500.00");
        assertThat(asOf(FIGURES.get(2) + toNorth, asked, admin, DashboardDtos.TopOutstanding.class)
                .customers()).extracting(DashboardDtos.OutstandingCustomer::customerName)
                .containsExactly("Away Ltd");
        assertThat(total(asOf(FIGURES.get(3) + toNorth, asked, admin,
                DashboardDtos.MonthlySeries.class))).isEqualByComparingTo("300.00");
        assertThat(asOf(FIGURES.get(4) + toNorth, asked, admin, DashboardDtos.TopPaying.class)
                .customers()).extracting(DashboardDtos.PayingCustomer::customerName)
                .containsExactly("Away Ltd");

        // Not vacuous: unnarrowed, the same question as of the same date counts both branches.
        assertThat(total(asOf(FIGURES.get(0), asked, admin, DashboardDtos.MonthlySeries.class)))
                .isEqualByComparingTo("1500.00");
        // And the answer says which branch it covered, exactly as it does on the live path.
        assertThat(asOf(FIGURES.get(0) + toNorth, asked, admin, DashboardDtos.MonthlySeries.class)
                .regionCoverage().regions()).extracting(DashboardDtos.RegionRef::code)
                .containsExactly("NORTH");
    }

    /**
     * THE LIVE PATH IS UNTOUCHED, MEASURED RATHER THAN ARGUED. A reader who works in exactly one
     * branch drives all five figures with no {@code asOf} anywhere, and not one statement names a
     * mirror table or the placement ledger: as-of costs the ordinary request nothing at all (B3).
     */
    @Test
    void theLiveFiguresAskNoMirrorTableAndNoPlacementLedger() throws Exception {
        User cashier = user("cara.cashier", "CASHIER");
        raise(acme, today.minusDays(60), today.minusDays(40), "1000.00", admin);

        for (String url : FIGURES) {
            List<String> sql = CountingStatements.capture(() -> body(get(url).with(as(cashier))));
            assertThat(sql).describedAs("%s read history on the live path", url)
                    .noneMatch(s -> s.contains("_history"));
        }
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A reader whose book really is a book: no SCOPE_OVERRIDE, assignable as both a sales and a
     * collection POC, so {@code collected()} takes its PaymentAllocation branch. Copied from
     * DashboardRegionTest, which needed the same shape for the same reason (B1).
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

    /**
     * A POC works where the records they own are: from R8 a per-record POC field is refused for
     * somebody who cannot MANAGE that branch, and {@code user(name, role)} staffs a person only at
     * the levels their own role implies — all VIEW, for this one. MANAGE covers VIEW, so what this
     * reader can SEE is unchanged (B1, R8).
     */
    private User bookUser(String username) {
        User u = user(username, bookRole().getName());
        userRegionGrantRepository.save(com.geneinvoice.region.UserRegionGrant.builder()
                .userId(u.getId()).regionId(defaultRegion().getId())
                .right(com.geneinvoice.region.RegionRight.MANAGE).build());
        return u;
    }

    /**
     * An opening placement in this account's own branch. IntegrationTestBase.customer(name) writes
     * NO customer_region_history row — only CustomerService.create and the region backfill do — and
     * under an open as-of date the region axis ANDs a clause over that ledger, so a reader who is
     * not a wildcard holder would see none of these accounts as of any date. B3-CONTEXT named this
     * landmine; this is the four lines that disarm it (B1, B3).
     */
    private void placeForever(Customer c) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(c.getRegion().getId())
                .validFrom(today.minusYears(2)).validTo(null).build());
    }

    private static Instant noon(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    }

    /**
     * A real create through the real service with the WRITE-side clock held at the day in question,
     * so the mirror row the history writer leaves behind is dated then. Ten lines of a hundred
     * rather than an arbitrary unit price, so the total reads as the round number asserted.
     */
    private Invoice raise(Customer c, LocalDate on, LocalDate dueOn, String total, User salesPoc) {
        clock.freezeAt(noon(on));
        int quantity = new BigDecimal(total).divide(new BigDecimal("100.00")).intValue();
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), noon(on), dueOn, PaymentTerm.CUSTOM, null, salesPoc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity,
                        new BigDecimal("100.00")))));
        clock.release();
        return invoice;
    }

    private void edit(Invoice invoice, String notes, LocalDate on) {
        clock.freezeAt(noon(on));
        invoiceService.update(invoice.getId(), new InvoiceDtos.UpdateInvoiceRequest(notes, null));
        clock.release();
    }

    /**
     * A real payment, dated. {@code Payment.onCreate} stamps paidAt from the wall clock and the
     * request carries no date of its own, so a figure that groups money by the month it landed in
     * needs it set — saved inside the same frozen window, so the mirror version that carries it is
     * dated then too and there is no gap in the chain (B3).
     */
    private Payment pay(Customer c, Invoice invoice, String amount, LocalDate on, User poc) {
        clock.freezeAt(noon(on));
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                c.getId(), new BigDecimal(amount), "CASH", null,
                invoice == null ? List.of() : List.of(invoice.getId()), poc.getId(), null));
        paid.setPaidAt(noon(on));
        Payment dated = paymentRepository.saveAndFlush(paid);
        clock.release();
        return dated;
    }

    private void voidAt(Payment paid, LocalDate on) {
        clock.freezeAt(noon(on));
        paymentService.voidPayment(paid.getId());
        clock.release();
    }

    private void crossAllocate(Payment payment, Invoice invoice, String amount, LocalDate on) {
        clock.freezeAt(noon(on));
        allocationRepository.saveAndFlush(PaymentAllocation.builder()
                .payment(payment).invoice(invoice).amount(new BigDecimal(amount)).build());
        clock.release();
    }

    private void rename(Customer c, String name, LocalDate on) {
        clock.freezeAt(noon(on));
        Customer fresh = customerRepository.findById(c.getId()).orElseThrow();
        fresh.setName(name);
        customerRepository.saveAndFlush(fresh);
        clock.release();
    }

    // ------------------------------------------------------------------ requests

    /**
     * ONE REQUEST ANSWERED OFF THE MIRROR AS OF {@code date}, through the whole servlet path, with
     * the context opened on the test thread rather than by {@code ?asOf}.
     *
     * <p>THIS IS NOT A SHORTCUT AND IT IS THE ONLY WAY TO ASK THE QUESTION. AsOfDates.parse serves
     * any date that is not in the past LIVE, so {@code ?asOf=<today>} can never reach the mirror;
     * comparing the two code paths at the same moment is exactly what the safety net has to do.
     * AsOfInterceptor.preHandle returns immediately when the request carries no {@code asOf}
     * parameter, so it neither refuses this nor replaces the state — and its afterCompletion clears
     * the thread, which is why this helper serves one request per call.
     */
    private String asOfBody(String url, LocalDate date, User who) throws Exception {
        RequestPostProcessor principal = as(who);
        try (AsOfContext.Handle handle = AsOfContext.open(date)) {
            return body(get(url).with(principal));
        }
    }

    /** One request carrying a real {@code ?asOf}, which is how a client asks about the past. */
    private String asOfResponse(String url, LocalDate date, User who) throws Exception {
        return body(get(url + (url.contains("?") ? "&" : "?") + "asOf=" + date).with(as(who)));
    }

    private String body(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private <T> T asOf(String url, LocalDate date, User who, Class<T> type) throws Exception {
        return objectMapper.readValue(asOfResponse(url, date, who), type);
    }

    private <T> T live(String url, User who, Class<T> type) throws Exception {
        return objectMapper.readValue(body(get(url).with(as(who))), type);
    }

    /** The figure read off the mirror at {@code date}, with the as-of stamp itself taken off. */
    private JsonNode asOfTree(String url, LocalDate date, User who) throws Exception {
        return tree(asOfBody(url, date, who));
    }

    private JsonNode liveTree(String url, User who) throws Exception {
        return tree(body(get(url).with(as(who))));
    }

    /**
     * The payload without its as-of stamp. The stamp is the ONE field that is supposed to differ
     * between the two answers — null live, a date and an origin as of a date — so comparing the
     * rest is comparing everything this test is about.
     */
    private JsonNode tree(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (node instanceof ObjectNode object) object.remove("asOf");
        return node;
    }

    private static BigDecimal total(DashboardDtos.MonthlySeries series) {
        return series.months().stream().map(DashboardDtos.MonthPoint::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
