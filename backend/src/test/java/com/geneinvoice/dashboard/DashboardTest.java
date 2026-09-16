package com.geneinvoice.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard's figures follow the scope of the list each one summarises: everything for staff
 * who may see everything, the book for a POC limited to it, and a customer login's own account.
 */
class DashboardTest extends IntegrationTestBase {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DashboardService dashboardService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    User sales;
    User otherSales;
    User collector;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cora.collect", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    private Invoice invoice(Customer c, User rep, String amount, Instant date) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), date, null,
                rep.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(amount)))));
    }

    private Payment pay(Customer c, String amount, List<Long> invoiceIds) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(amount), "Cash", null, invoiceIds, collector.getId(), null));
    }

    private JsonNode read(String path, User caller) throws Exception {
        String body = mockMvc.perform(get(path).with(as(caller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** The current month's point of a series. */
    private static JsonNode thisMonth(JsonNode series) {
        JsonNode months = series.get("months");
        JsonNode last = months.get(months.size() - 1);
        assertThat(last.get("month").asText()).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        return last;
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant();
    }

    @Test
    void billedByMonthFillsEmptyMonthsSplitsAtUtcMidnightAndSkipsCancelled() {
        invoice(acme, sales, "100.00", Instant.parse("2026-08-31T23:59:59Z"));
        invoice(acme, sales, "40.00", Instant.parse("2026-09-01T00:00:00Z"));
        Invoice cancelled = invoice(acme, sales, "999.00", Instant.parse("2026-09-10T10:00:00Z"));
        invoiceService.cancel(cancelled.getId());
        invoice(acme, sales, "555.00", Instant.parse("2026-06-30T12:00:00Z"));

        DashboardDtos.MonthlySeries series = dashboardService.billedByMonth(3, TODAY);

        assertThat(series.coverage()).isEqualTo(DashboardDtos.Coverage.ALL);
        assertThat(series.months()).extracting(DashboardDtos.MonthPoint::month)
                .containsExactly("2026-07", "2026-08", "2026-09");
        assertThat(series.months()).extracting(p -> p.amount().toPlainString())
                .containsExactly("0.00", "100.00", "40.00");
        assertThat(series.months()).extracting(DashboardDtos.MonthPoint::count)
                .containsExactly(0L, 1L, 1L);
    }

    @Test
    void outstandingIsBucketedByDaysSinceTheInvoiceDate() {
        invoice(acme, sales, "100.00", at(TODAY, 9));
        invoice(acme, sales, "100.00", at(TODAY.minusDays(30), 9));
        Invoice partlyPaid = invoice(acme, sales, "100.00", at(TODAY.minusDays(31), 9));
        pay(acme, "40.00", List.of(partlyPaid.getId()));
        invoice(acme, sales, "100.00", at(TODAY.minusDays(90), 9));
        invoice(globex, sales, "100.00", at(TODAY.minusDays(91), 9));
        Invoice settled = invoice(globex, sales, "100.00", at(TODAY.minusDays(100), 9));
        pay(globex, "100.00", List.of(settled.getId()));
        Invoice cancelled = invoice(globex, sales, "100.00", at(TODAY.minusDays(120), 9));
        invoiceService.cancel(cancelled.getId());

        List<DashboardDtos.AgeBucket> buckets = dashboardService.outstandingByAge(TODAY).buckets();

        assertThat(buckets).extracting(DashboardDtos.AgeBucket::label)
                .containsExactly("0–30 days", "31–60 days", "61–90 days", "Over 90 days");
        assertThat(buckets).extracting(b -> b.amount().toPlainString())
                .containsExactly("200.00", "60.00", "100.00", "100.00");
        assertThat(buckets).extracting(DashboardDtos.AgeBucket::count)
                .containsExactly(2L, 1L, 1L, 1L);
    }

    @Test
    void customersOwingTheMostComeFirstAndSettledOnesAreLeftOut() {
        Customer initech = customer("Initech");
        invoice(acme, sales, "150.00", at(TODAY.minusDays(40), 9));
        invoice(acme, sales, "100.00", at(TODAY.minusDays(5), 9));
        invoice(globex, sales, "300.00", at(TODAY.minusDays(2), 9));
        Invoice paid = invoice(initech, sales, "900.00", at(TODAY.minusDays(1), 9));
        pay(initech, "900.00", List.of(paid.getId()));

        List<DashboardDtos.OutstandingCustomer> top = dashboardService.topOutstanding(5).customers();

        assertThat(top).extracting(DashboardDtos.OutstandingCustomer::customerName)
                .containsExactly("Globex Corp", "Acme Ltd");
        assertThat(top.get(1).outstanding()).isEqualByComparingTo("250.00");
        assertThat(top.get(1).openInvoices()).isEqualTo(2);
        assertThat(top.get(1).oldestInvoiceDate()).isEqualTo(at(TODAY.minusDays(40), 9));
        assertThat(dashboardService.topOutstanding(1).customers()).hasSize(1);
    }

    /**
     * A Sales POC sees their own invoices but every payment. Their payment figures count only what
     * landed on their invoices, so billed and collected cover the same invoices.
     */
    @Test
    void aBookLimitedSalesPocCountsOnlyMoneyPaidAgainstTheirOwnInvoices() throws Exception {
        Instant month = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        invoice(acme, sales, "100.00", month.plusSeconds(60));
        invoice(acme, sales, "100.00", month.plusSeconds(120));
        invoice(acme, otherSales, "100.00", month.plusSeconds(180));
        invoice(globex, otherSales, "300.00", month.plusSeconds(240));
        // Oldest first: 100 + 100 on Sam's invoices, the last 50 on Sid's.
        pay(acme, "250.00", null);
        pay(globex, "300.00", null);

        JsonNode samBilled = read("/api/dashboard/billed-by-month", sales);
        assertThat(samBilled.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(samBilled.get("months")).hasSize(12);
        assertThat(thisMonth(samBilled).get("amount").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode samCollected = read("/api/dashboard/collected-by-month", sales);
        assertThat(samCollected.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(thisMonth(samCollected).get("amount").decimalValue()).isEqualByComparingTo("200.00");
        // One payment, even though it was split across two of Sam's invoices.
        assertThat(thisMonth(samCollected).get("count").asLong()).isEqualTo(1);

        JsonNode sidCollected = read("/api/dashboard/collected-by-month", otherSales);
        assertThat(thisMonth(sidCollected).get("amount").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(thisMonth(sidCollected).get("count").asLong()).isEqualTo(2);

        JsonNode samTop = read("/api/dashboard/top-paying-customers", sales);
        assertThat(samTop.get("customers")).hasSize(1);
        assertThat(samTop.get("customers").get(0).get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(samTop.get("customers").get(0).get("collected").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode samOwing = read("/api/dashboard/top-outstanding-customers", sales);
        assertThat(samOwing.get("customers")).isEmpty();

        JsonNode adminCollected = read("/api/dashboard/collected-by-month", admin);
        assertThat(adminCollected.get("coverage").asText()).isEqualTo("ALL");
        assertThat(thisMonth(adminCollected).get("amount").decimalValue()).isEqualByComparingTo("550.00");

        JsonNode adminTop = read("/api/dashboard/top-paying-customers", admin);
        assertThat(adminTop.get("customers").get(0).get("customerName").asText()).isEqualTo("Globex Corp");
        assertThat(adminTop.get("customers").get(1).get("collected").decimalValue()).isEqualByComparingTo("250.00");
    }

    @Test
    void overpaymentCountsInFullForStaffWhoSeeEveryPayment() throws Exception {
        invoice(acme, sales, "100.00", null);
        pay(acme, "130.00", null);

        JsonNode collected = read("/api/dashboard/collected-by-month", collector);
        assertThat(collected.get("coverage").asText()).isEqualTo("ALL");
        assertThat(thisMonth(collected).get("amount").decimalValue()).isEqualByComparingTo("130.00");
    }

    @Test
    void aCustomerLoginSeesItsOwnFiguresButNoCustomerRankings() throws Exception {
        invoice(acme, sales, "200.00", null);
        invoice(globex, sales, "300.00", null);
        pay(acme, "80.00", null);
        pay(globex, "300.00", null);
        User acmeLogin = customerUser("acme.login", acme.getId());

        JsonNode billed = read("/api/dashboard/billed-by-month", acmeLogin);
        assertThat(billed.get("coverage").asText()).isEqualTo("OWN");
        assertThat(thisMonth(billed).get("amount").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode collected = read("/api/dashboard/collected-by-month", acmeLogin);
        assertThat(collected.get("coverage").asText()).isEqualTo("OWN");
        assertThat(thisMonth(collected).get("amount").decimalValue()).isEqualByComparingTo("80.00");

        JsonNode aging = read("/api/dashboard/outstanding-by-age", acmeLogin);
        assertThat(aging.get("buckets").get(0).get("amount").decimalValue()).isEqualByComparingTo("120.00");

        mockMvc.perform(get("/api/dashboard/top-outstanding-customers").with(as(acmeLogin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/top-paying-customers").with(as(acmeLogin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void eachEndpointNeedsThePrivilegeOfTheListItSummarises() throws Exception {
        User invoicesOnly = withPrivileges("DASH_INVOICES_ONLY", Privileges.INVOICE_VIEW);
        User paymentsOnly = withPrivileges("DASH_PAYMENTS_ONLY", Privileges.PAYMENT_VIEW);

        for (String path : List.of("/api/dashboard/billed-by-month", "/api/dashboard/outstanding-by-age",
                "/api/dashboard/top-outstanding-customers")) {
            mockMvc.perform(get(path).with(as(invoicesOnly))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(as(paymentsOnly))).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/dashboard/collected-by-month", "/api/dashboard/top-paying-customers")) {
            mockMvc.perform(get(path).with(as(paymentsOnly))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(as(invoicesOnly))).andExpect(status().isForbidden());
        }
    }

    @Test
    void monthsAndLimitOutsideTheirRangeAreRefused() throws Exception {
        mockMvc.perform(get("/api/dashboard/billed-by-month?months=0").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/collected-by-month?months=25").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/top-outstanding-customers?limit=21").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/top-paying-customers?limit=0").with(as(admin)))
                .andExpect(status().isBadRequest());
    }

    private User withPrivileges(String roleName, String... privileges) {
        Role role = roleRepository.findByName(roleName).orElseGet(() -> {
            var set = new HashSet<com.geneinvoice.privilege.Privilege>();
            for (String p : privileges) set.add(privilegeRepository.findByName(p).orElseThrow());
            return roleRepository.save(Role.builder().name(roleName).description(roleName)
                    .privileges(set).build());
        });
        return user(roleName.toLowerCase(), role.getName());
    }
}
