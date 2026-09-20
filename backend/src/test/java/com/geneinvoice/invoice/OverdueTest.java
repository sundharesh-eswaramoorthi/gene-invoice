package com.geneinvoice.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Overdue is read from the clock and never stored (D3), which is what lets the same row turn late
 * overnight with nothing written to it. This covers that, the boundary it turns on (AC-A9), and
 * the places it shows: the list filter and sort under every scope (AC-A6), the tiles (AC-A7) and
 * the export.
 */
class OverdueTest extends IntegrationTestBase {

    static final LocalDate TODAY = InvoiceDates.today();

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AuditService auditService;

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

    /** Raised a year back, so any due date a test asks for is still on or after the invoice date. */
    private Invoice invoice(Customer c, User poc, String amount, LocalDate due) {
        Instant raised = TODAY.minusDays(365).atStartOfDay(ZoneOffset.UTC).toInstant();
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), raised, due,
                null, null, poc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(amount)))));
    }

    private void pay(Customer c, String amount, List<Long> invoiceIds) {
        paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(amount), "Cash", null, invoiceIds, collector.getId(), null));
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The invoice numbers a list request returns, in the order it returned them. */
    private List<String> numbers(JsonNode page) {
        return page.findValues("invoiceNumber").stream().map(JsonNode::asText).toList();
    }

    // ---- AC-A4 and AC-A9: the clock alone decides -----------------------------------

    @Test
    void anInvoiceTurnsOverdueAsTheDayTurnsWithNothingWrittenToIt() {
        Invoice inv = invoice(acme, sales, "100.00", TODAY.plusDays(3));
        int auditsBefore = auditService.historyFor(InvoiceService.ENTITY, inv.getId()).size();

        assertThat(inv.isOverdue(TODAY)).isFalse();
        // Due today is not late; it is late the next morning, and by one day (AC-A9).
        assertThat(inv.isOverdue(inv.getDueDate())).isFalse();
        assertThat(inv.daysOverdue(inv.getDueDate())).isZero();
        assertThat(inv.isOverdue(inv.getDueDate().plusDays(1))).isTrue();
        assertThat(inv.daysOverdue(inv.getDueDate().plusDays(1))).isEqualTo(1);
        assertThat(inv.daysOverdue(inv.getDueDate().plusDays(12))).isEqualTo(12);

        Invoice reread = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(reread.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(auditService.historyFor(InvoiceService.ENTITY, inv.getId())).hasSize(auditsBefore);
    }

    @Test
    void anInvoiceThatOwesNothingOrWasCancelledIsNeverOverdue() {
        Invoice settled = invoice(acme, sales, "100.00", TODAY.minusDays(40));
        pay(acme, "100.00", List.of(settled.getId()));
        Invoice cancelled = invoice(globex, sales, "100.00", TODAY.minusDays(40));
        invoiceService.cancel(cancelled.getId());
        Invoice partly = invoice(globex, sales, "100.00", TODAY.minusDays(40));
        pay(globex, "40.00", List.of(partly.getId()));

        Invoice paid = invoiceRepository.findById(settled.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(paid.isOverdue(TODAY)).isFalse();
        assertThat(paid.daysOverdue(TODAY)).isZero();

        assertThat(invoiceRepository.findById(cancelled.getId()).orElseThrow().isOverdue(TODAY)).isFalse();
        // Part-paid still owes something, so it is still late.
        assertThat(invoiceRepository.findById(partly.getId()).orElseThrow().isOverdue(TODAY)).isTrue();
    }

    @Test
    void theDetailSaysHowLateItIs() throws Exception {
        Invoice late = invoice(acme, sales, "100.00", TODAY.minusDays(12));

        mockMvc.perform(get("/api/invoices/" + late.getId()).with(as(admin)))
                .andExpect(jsonPath("$.overdue").value(true))
                .andExpect(jsonPath("$.daysOverdue").value(12));
    }

    // ---- AC-A6: filtering and sorting, server-side and scoped -----------------------

    @Test
    void theListFiltersToOverdueOnlyAndSortsByDueDate() throws Exception {
        Invoice late = invoice(acme, sales, "100.00", TODAY.minusDays(5));
        Invoice later = invoice(acme, sales, "100.00", TODAY.minusDays(40));
        Invoice dueToday = invoice(acme, sales, "100.00", TODAY);
        Invoice ahead = invoice(acme, sales, "100.00", TODAY.plusDays(10));

        JsonNode overdue = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "overdue:eq:true").param("sort", "dueDate,asc"));
        assertThat(numbers(overdue))
                .containsExactly(later.getInvoiceNumber(), late.getInvoiceNumber());
        assertThat(overdue.get("totalElements").asLong()).isEqualTo(2);

        // The other side of the same filter: an invoice due today is not late (AC-A9).
        JsonNode notOverdue = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "overdue:eq:false").param("sort", "dueDate,asc"));
        assertThat(numbers(notOverdue))
                .containsExactly(dueToday.getInvoiceNumber(), ahead.getInvoiceNumber());

        JsonNode newestFirst = getJson(get("/api/invoices").with(as(admin))
                .param("sort", "dueDate,desc"));
        assertThat(numbers(newestFirst)).startsWith(ahead.getInvoiceNumber());
    }

    @Test
    void aDueDateRangeFilterPicksOutAWindow() throws Exception {
        invoice(acme, sales, "100.00", TODAY.minusDays(40));
        Invoice inWindow = invoice(acme, sales, "100.00", TODAY.minusDays(20));
        invoice(acme, sales, "100.00", TODAY.plusDays(5));

        JsonNode page = getJson(get("/api/invoices").with(as(admin))
                .param("filter", "dueDate:between:" + TODAY.minusDays(30) + "," + TODAY.minusDays(1)));

        assertThat(numbers(page)).containsExactly(inWindow.getInvoiceNumber());
    }

    @Test
    void aFilterValueThatIsNotTrueOrFalseIsRefused() throws Exception {
        invoice(acme, sales, "100.00", TODAY.minusDays(5));

        mockMvc.perform(get("/api/invoices").with(as(admin)).param("filter", "overdue:eq:maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("Expected true or false")));
    }

    /** The scope rules still apply inside the filter: a POC's overdue list is their book's. */
    @Test
    void aPocFilteringByOverdueSeesOnlyTheirOwnBook() throws Exception {
        Invoice mine = invoice(acme, sales, "100.00", TODAY.minusDays(5));
        Invoice theirs = invoice(globex, otherSales, "100.00", TODAY.minusDays(5));

        JsonNode page = getJson(get("/api/invoices").with(as(sales))
                .param("filter", "overdue:eq:true"));

        assertThat(numbers(page)).containsExactly(mine.getInvoiceNumber());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        // Admin, who may see everything, sees both.
        assertThat(numbers(getJson(get("/api/invoices").with(as(admin))
                .param("filter", "overdue:eq:true"))))
                .containsExactlyInAnyOrder(mine.getInvoiceNumber(), theirs.getInvoiceNumber());
    }

    @Test
    void aCustomerLoginFilteringByOverdueSeesOnlyItsOwn() throws Exception {
        Invoice mine = invoice(acme, sales, "100.00", TODAY.minusDays(5));
        invoice(globex, sales, "100.00", TODAY.minusDays(5));
        User acmeLogin = customerUser("acme.login", acme.getId());

        JsonNode page = getJson(get("/api/invoices").with(as(acmeLogin))
                .param("filter", "overdue:eq:true"));

        assertThat(numbers(page)).containsExactly(mine.getInvoiceNumber());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    }

    // ---- AC-A7: the tiles -----------------------------------------------------------

    @Test
    void theTilesCountOverdueMoneyOverTheWholeFilteredSet() throws Exception {
        invoice(acme, sales, "100.00", TODAY.minusDays(5));
        Invoice partly = invoice(acme, sales, "200.00", TODAY.minusDays(40));
        pay(acme, "50.00", List.of(partly.getId()));
        invoice(acme, sales, "400.00", TODAY.plusDays(10));
        Invoice cancelled = invoice(acme, sales, "900.00", TODAY.minusDays(40));
        invoiceService.cancel(cancelled.getId());
        invoice(globex, otherSales, "700.00", TODAY.minusDays(3));

        // 100 still owed on the first, 150 on the part-paid one, 700 on Globex's.
        JsonNode all = getJson(get("/api/invoices/summary").with(as(admin)));
        assertThat(all.get("overdueAmount").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(all.get("overdueCount").asLong()).isEqualTo(3);

        // The tiles follow the filter, not the page (AC-E1).
        JsonNode acmeOnly = getJson(get("/api/invoices/summary").with(as(admin))
                .param("customerId", String.valueOf(acme.getId())));
        assertThat(acmeOnly.get("overdueAmount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(acmeOnly.get("overdueCount").asLong()).isEqualTo(2);

        // And the caller's scope: Sam's book has none of Globex's.
        JsonNode sams = getJson(get("/api/invoices/summary").with(as(sales)));
        assertThat(sams.get("overdueAmount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(sams.get("overdueCount").asLong()).isEqualTo(2);
    }

    @Test
    void theTilesReadZeroWhenNothingIsLate() throws Exception {
        invoice(acme, sales, "100.00", TODAY.plusDays(10));

        JsonNode tiles = getJson(get("/api/invoices/summary").with(as(admin)));

        assertThat(tiles.get("overdueAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(tiles.get("overdueCount").asLong()).isZero();
        assertThat(tiles.get("outstanding").decimalValue()).isEqualByComparingTo("100.00");
    }

    // ---- AC-A6 on the customer record, and the export --------------------------------

    @Test
    void theCustomerRecordSaysHowMuchOfWhatIsOwedIsLate() throws Exception {
        invoice(acme, sales, "100.00", TODAY.minusDays(5));
        invoice(acme, sales, "400.00", TODAY.plusDays(10));
        Invoice cancelled = invoice(acme, sales, "900.00", TODAY.minusDays(40));
        invoiceService.cancel(cancelled.getId());

        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(admin)))
                .andExpect(jsonPath("$.outstanding").value(500.00))
                .andExpect(jsonPath("$.overdueAmount").value(100.00));
    }

    @Test
    void theExportCarriesTheDueDateAndWhetherItIsLate() throws Exception {
        Invoice late = invoice(acme, sales, "100.00", TODAY.minusDays(5));
        Invoice ahead = invoice(acme, sales, "100.00", TODAY.plusDays(5));

        String csv = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "EXPORT", "sort", "dueDate,asc",
                                "ids", List.of(late.getId(), ahead.getId())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith(
                "Invoice #,Customer,Date,Due date,Total,Paid,Balance,Status,Overdue,Sales POC");
        List<String> lines = List.of(csv.split("\r\n"));
        assertThat(lines.get(1)).contains("," + late.getDueDate() + ",").endsWith(",true,sam.sales");
        assertThat(lines.get(2)).contains("," + ahead.getDueDate() + ",").endsWith(",false,sam.sales");
    }

    /** Overdue is a filter and not a value, so there is nothing to sort by but the date. */
    @Test
    void sortingByOverdueIsRefused() throws Exception {
        invoice(acme, sales, "100.00", TODAY.minusDays(5));

        mockMvc.perform(get("/api/invoices").with(as(admin)).param("sort", "overdue,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("overdue")));
    }
}
