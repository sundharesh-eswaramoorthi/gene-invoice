package com.geneinvoice.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feature D.3: bulk actions are per-record, report every id, and never widen the caller's reach. */
class BulkActionTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;

    User admin;
    User sales;
    User otherSales;
    User collections;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        globex = customer("Globex Corp");
        widget = product("Widget", "10.00");
        actAs(admin);
    }

    private Invoice invoice(Customer c, String unitPrice, User poc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, poc.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal(unitPrice)))));
    }

    private JsonNode bulk(User caller, String path, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(path).with(as(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> request(String action, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- AC-D5: partial failure reports each row and drops none -----------------

    @Test
    void aPartialFailureReportsWhichRowsSucceededAndWhichDidNot() throws Exception {
        Invoice cancellable = invoice(acme, "10.00", sales);
        Invoice paid = invoice(acme, "10.00", sales);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(paid.getId()),
                collections.getId(), null));

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(cancellable.getId(), paid.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").size()).isEqualTo(1);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(cancellable.getId());
        assertThat(result.get("failed").size()).isEqualTo(1);
        assertThat(result.get("failed").get(0).get("id").asLong()).isEqualTo(paid.getId());
        assertThat(result.get("failed").get(0).get("reason").asText())
                .contains("Cannot cancel an invoice with payments");

        // Every requested id is accounted for, and the good one really did commit.
        int accountedFor = result.get("succeeded").size() + result.get("failed").size()
                + result.get("skipped").size();
        assertThat(accountedFor).isEqualTo(result.get("requested").asInt());
        assertThat(invoiceRepository.findById(cancellable.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(paid.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void aFailingRowDoesNotRollBackTheRowsThatAlreadySucceeded() throws Exception {
        Invoice a = invoice(acme, "10.00", sales);
        Invoice b = invoice(acme, "10.00", sales);
        Invoice c = invoice(acme, "10.00", sales);
        invoiceService.cancel(b.getId()); // already cancelled: will fail in the batch

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(a.getId(), b.getId(), c.getId())));

        assertThat(result.get("succeeded").size()).isEqualTo(2);
        assertThat(result.get("failed").size()).isEqualTo(1);
        assertThat(invoiceRepository.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void ineligibleRowsAreReportedAsSkippedRatherThanFailed() throws Exception {
        var alreadyActive = productRepository.save(
                com.geneinvoice.product.Product.builder()
                        .name("Gizmo").price(new BigDecimal("5.00")).active(true).build());

        JsonNode result = bulk(admin, "/api/products/bulk",
                request("ACTIVATE", "ids", List.of(alreadyActive.getId())));

        assertThat(result.get("succeeded").size()).isZero();
        assertThat(result.get("failed").size()).isZero();
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("reason").asText()).isEqualTo("Already active");
    }

    // ---- AC-D6: a bulk action never reaches rows the caller may not change ------

    @Test
    void idsOutsideTheCallersScopeAreExcludedNotAttempted() throws Exception {
        Invoice mine = invoice(acme, "10.00", sales);
        Invoice theirs = invoice(globex, "10.00", otherSales);

        // sam.sales is locked to his own book, so `theirs` is not in his permitted set at all.
        JsonNode result = bulk(sales, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(mine.getId(), theirs.getId())));

        // Asked for two, acted on one: the other is reported as skipped, never silently dropped.
        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(mine.getId());
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(theirs.getId());
        assertThat(invoiceRepository.findById(theirs.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void anUnknownIdIsReportedAsSkippedWithoutSayingItDoesNotExist() throws Exception {
        Invoice own = invoice(acme, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk",
                request("CANCEL", "ids", List.of(own.getId(), 99999999L)));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(own.getId());
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(99999999L);
        assertThat(result.get("skipped").get(0).get("reason").asText())
                .isEqualTo("Not found, or outside your scope or the current filter");
    }

    @Test
    void aCustomerScopedUserCannotReachAnotherCustomersRowsThroughABulkCall() throws Exception {
        Invoice theirs = invoice(globex, "10.00", sales);
        User acmeUser = customerUser("acme.user", acme.getId());

        mockMvc.perform(post("/api/invoices/bulk").with(as(acmeUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("CANCEL", "ids", List.of(theirs.getId())))))
                .andExpect(status().isForbidden());

        assertThat(invoiceRepository.findById(theirs.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void anUnknownBulkActionIsRejectedWithTheListOfValidOnes() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("DROP_TABLE", "ids", List.of(inv.getId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Unknown bulk action")));
    }

    // ---- AC-D7: "select all N matching the filter" spans the whole filtered set --

    @Test
    void selectAllMatchingTheFilterAppliesBeyondTheLoadedPage() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", sales);
        invoice(globex, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk", request("CANCEL",
                "selectAllMatchingFilter", true,
                "filters", List.of("customerId:eq:" + acme.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(25);
        assertThat(result.get("succeeded").size()).isEqualTo(25);
        assertThat(invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(globex.getId()))
                .allMatch(i -> i.getStatus() != InvoiceStatus.CANCELLED);
    }

    @Test
    void theExactCountIsAvailableToTheClientBeforeItConfirms() throws Exception {
        for (int i = 0; i < 25; i++) invoice(acme, "10.00", sales);
        invoice(globex, "10.00", sales);

        // The list response the UI already has states the count it would be acting on.
        mockMvc.perform(get("/api/invoices").with(as(admin))
                        .param("size", "10")
                        .param("filter", "customerId:eq:" + acme.getId()))
                .andExpect(jsonPath("$.totalElements").value(25));
    }

    @Test
    void aBulkCallWithNeitherIdsNorSelectAllIsRejected() throws Exception {
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("CANCEL"))))
                .andExpect(status().isBadRequest());
    }

    // ---- AC-D8: one audit entry per affected record -----------------------------

    @Test
    void aBulkReassignmentWritesOneAuditEntryPerRecord() throws Exception {
        Invoice a = invoice(acme, "10.00", sales);
        Invoice b = invoice(acme, "10.00", sales);

        JsonNode result = bulk(admin, "/api/invoices/bulk", request("REASSIGN_SALES_POC",
                "ids", List.of(a.getId(), b.getId()),
                "params", Map.of("userId", otherSales.getId())));

        assertThat(result.get("succeeded").size()).isEqualTo(2);
        for (Invoice inv : List.of(a, b)) {
            assertThat(invoiceRepository.findById(inv.getId()).orElseThrow()
                    .getSalesPoc().getId()).isEqualTo(otherSales.getId());
            mockMvc.perform(get("/api/audit").with(as(admin))
                            .param("entityType", "INVOICE")
                            .param("entityId", inv.getId().toString()))
                    .andExpect(jsonPath("$[?(@.action=='INVOICE_UPDATED')]").isNotEmpty());
        }
    }

    @Test
    void reassignPocRequiresTheTargetUser() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);
        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("REASSIGN_SALES_POC", "ids", List.of(inv.getId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("params.userId")));
    }

    // ---- export ------------------------------------------------------------------

    @Test
    void exportingTheSelectionReturnsCsvWithTheCallersVisibleColumns() throws Exception {
        Invoice inv = invoice(acme, "10.00", sales);

        String csv = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("EXPORT", "ids", List.of(inv.getId())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).startsWith("Invoice #,Customer,Date,Total,Paid,Balance,Status,Sales POC");
        assertThat(csv).contains("Acme Ltd").contains("sam.sales");
    }

    @Test
    void csvEscapesSeparatorsAndNeutralisesFormulaPrefixes() throws Exception {
        Customer tricky = customerRepository.save(
                Customer.builder().name("=CMD(),\"Ltd\"").build());
        Invoice inv = invoice(tricky, "10.00", sales);

        String csv = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("EXPORT", "ids", List.of(inv.getId())))))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("\"'=CMD(),\"\"Ltd\"\"\"");
    }

    // ---- notifications bulk ------------------------------------------------------

    @Test
    void markingNotificationsReadInBulkTouchesOnlyTheCallersOwn() throws Exception {
        var mine = notificationRepository.save(com.geneinvoice.notification.Notification.builder()
                .userId(admin.getId()).type("T").title("mine").build());
        var theirs = notificationRepository.save(com.geneinvoice.notification.Notification.builder()
                .userId(sales.getId()).type("T").title("theirs").build());

        JsonNode result = bulk(admin, "/api/notifications/bulk",
                request("MARK_READ", "ids", List.of(mine.getId(), theirs.getId())));

        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(theirs.getId());
        assertThat(notificationRepository.findById(mine.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(theirs.getId()).orElseThrow().isRead()).isFalse();
    }
}
