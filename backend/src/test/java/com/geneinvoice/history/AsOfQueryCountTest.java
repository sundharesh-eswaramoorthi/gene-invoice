package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.MovableClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An as-of page costs a fixed number of version reads however many rows it has: the page and its count, then one
 * batch per kind of record the rows point at (§5.7.15). The live page reads no versions at all: its SQL is what it
 * always was.
 */
class AsOfQueryCountTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;

    User admin;
    List<Customer> customers = new ArrayList<>();
    List<Long> invoiceIds = new ArrayList<>();

    @BeforeEach
    void twentyInvoicesOverFiveCustomersYesterday() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        MovableClock.SHARED.set(Instant.parse("2026-04-07T10:00:00Z"));
        actAs(admin);
        List<Product> products = List.of(product("Widget", "10.00"), product("Gadget", "20.00"));
        User seller = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        for (int c = 0; c < 5; c++) customers.add(customer("Customer " + c));
        for (int i = 0; i < 20; i++) {
            invoiceIds.add(invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customers.get(i % 5).getId(), null, null, seller.getId(),
                    List.of(new InvoiceDtos.LineInput(products.get(0).getId(), 1, null),
                            new InvoiceDtos.LineInput(products.get(1).getId(), 2, null)))).getId());
        }
        MovableClock.SHARED.set(Instant.parse("2026-04-08T10:00:00Z"));
    }

    private JsonNode page(String asOf) throws Exception {
        return page("/api/invoices", asOf);
    }

    private JsonNode page(String url, String asOf, String... params) throws Exception {
        var request = get(url).param("size", "20").with(as(admin));
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        if (asOf != null) request.param("asOf", asOf);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void anAsOfPageReadsItsVersionsOnceForThePageAndOnceForTheCount() throws Exception {
        JsonNode[] seen = new JsonNode[1];
        long invoiceReads = CountingStatements.reads("invoice_versions", () -> seen[0] = page("2026-04-07"));
        assertThat(seen[0].get("content")).hasSize(20);
        assertThat(seen[0].get("totalElements").asInt()).isEqualTo(20);
        assertThat(invoiceReads).as("the page and its count").isEqualTo(2);

        long customerReads = CountingStatements.reads("customer_versions", () -> page("2026-04-07"));
        assertThat(customerReads).as("one batch for the five customers of twenty rows").isEqualTo(1);
        long productReads = CountingStatements.reads("product_versions", () -> page("2026-04-07"));
        assertThat(productReads).as("one batch for the products on forty lines").isEqualTo(1);
    }

    @Test
    void theLivePageReadsNoVersions() throws Exception {
        assertThat(CountingStatements.reads("_versions", () -> page(null))).isZero();
        assertThat(CountingStatements.reads("_versions", () -> page("2026-04-08")))
                .as("today is the live view").isZero();
    }

    /**
     * A dispute names its customer and its target as they were: the customers, invoices and payments behind a page
     * are each read in one batch, so three rows cost what fifteen do (review of BE-09b).
     */
    @Test
    void anAsOfDisputePageReadsWhatItsRowsPointAtInBatches() throws Exception {
        MovableClock.SHARED.set(Instant.parse("2026-04-07T11:00:00Z"));
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        for (int c = 0; c < 5; c++) {
            Long customerId = customers.get(c).getId();
            actAs(admin);
            Long payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(customerId,
                    new BigDecimal("5.00"), "Cash", null, List.of(invoiceIds.get(c)), cara.getId(), null)).getId();
            actAs(customerUser("login." + c, customerId));
            disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE, invoiceIds.get(c),
                    "Wrong", null));
            disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                    invoiceIds.get(c + 5), "Wrong", null));
            disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.PAYMENT, payment,
                    "Paid twice", null));
        }
        MovableClock.SHARED.set(Instant.parse("2026-04-08T10:00:00Z"));
        String one = customers.get(0).getId().toString();

        JsonNode[] seen = new JsonNode[2];
        long disputeReads = CountingStatements.reads("dispute_versions",
                () -> seen[0] = page("/api/disputes", "2026-04-07"));
        assertThat(seen[0].get("content")).hasSize(15);
        assertThat(seen[0].get("content").get(0).get("customerName").asText()).startsWith("Customer ");
        assertThat(disputeReads).as("the page and its count").isEqualTo(2);
        seen[1] = page("/api/disputes", "2026-04-07", "customerId", one);
        assertThat(seen[1].get("content")).hasSize(3);

        for (String table : List.of("customer_versions", "invoice_versions", "payment_versions",
                "payment_allocation_versions", "product_versions")) {
            long fifteen = CountingStatements.reads(table, () -> page("/api/disputes", "2026-04-07"));
            long three = CountingStatements.reads(table, () -> page("/api/disputes", "2026-04-07", "customerId", one));
            assertThat(fifteen).as(table + ": fifteen rows read it as often as three").isEqualTo(three);
            assertThat(fifteen).as(table + ": a batch per kind of record looked up, not one per row")
                    .isLessThanOrEqualTo(3);
        }
    }
}
