package com.geneinvoice.promise;

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
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The promises list filtered by invoice — what the Promises tab on Invoice Details asks for. */
class PromiseInvoiceFilterTest extends IntegrationTestBase {

    @Autowired PaymentPromiseService promiseService;
    @Autowired InvoiceService invoiceService;
    @Autowired PocService pocService;
    @Autowired PaymentService paymentService;

    User admin;
    Customer acme;
    Product widget;

    Invoice a;
    Invoice b;
    Long onA;
    Long onBoth;
    Long onB;
    Long general;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        User collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);

        a = invoice();
        b = invoice();
        onA = promise(List.of(a.getId()));
        onBoth = promise(List.of(a.getId(), b.getId()));
        onB = promise(List.of(b.getId()));
        general = promise(null);
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private Long promise(List<Long> invoiceIds) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("50.00"), LocalDate.now(ZoneOffset.UTC).plusDays(1),
                null, null, invoiceIds, null)).id();
    }

    private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }

    private JsonNode filtered(String filter) throws Exception {
        return page(get("/api/promises").param("filter", filter));
    }

    @Test
    void theInvoicePromisesTabListsOnlyThePromisesCoveringThatInvoice() throws Exception {
        // The exact request the Invoice Details "Payment Promise" tab sends.
        JsonNode result = page(get("/api/promises")
                .param("size", "50")
                .param("customerId", acme.getId().toString())
                .param("invoiceId", a.getId().toString()));

        assertThat(ids(result)).containsExactlyInAnyOrder(onA, onBoth);
        assertThat(result.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void thePaymentPromisesTabListsOnlyThePromisesThatPaymentIsLinkedTo() throws Exception {
        User collections = userRepository.findByUsername("cara.collections").orElseThrow();
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("50.00"), "Cash", null, List.of(b.getId()), collections.getId(), null));

        // The exact request the Payment Details "Payment Promise" tab sends (D-26): the promises
        // covering invoice B, which the payment paid — not the whole customer's list.
        JsonNode result = page(get("/api/promises")
                .param("size", "50")
                .param("customerId", acme.getId().toString())
                .param("paymentId", payment.getId().toString()));

        assertThat(ids(result)).containsExactlyInAnyOrder(onBoth, onB);
        assertThat(ids(filtered("paymentId:eq:" + payment.getId()))).containsExactlyInAnyOrder(onBoth, onB);
        assertThat(ids(filtered("paymentId:isEmpty:"))).containsExactlyInAnyOrder(onA, general);
    }

    @Test
    void theInvoiceColumnSupportsEveryReferenceOperator() throws Exception {
        // A promise covering both invoices is still one row and one count.
        JsonNode anyOf = filtered("invoiceId:in:" + a.getId() + "," + b.getId());
        assertThat(ids(anyOf)).containsExactlyInAnyOrder(onA, onBoth, onB);
        assertThat(anyOf.get("totalElements").asLong()).isEqualTo(3);

        // Valueless operators keep the trailing colon of field:operator:value, as the app sends them.
        assertThat(ids(filtered("invoiceId:isEmpty:"))).containsExactly(general);
        assertThat(ids(filtered("invoiceId:isNotEmpty:"))).containsExactlyInAnyOrder(onA, onBoth, onB);
        assertThat(ids(filtered("invoiceId:neq:" + a.getId()))).containsExactlyInAnyOrder(onB, general);
    }

    @Test
    void theInvoiceColumnCannotBeSortedOrGivenANonIdValue() throws Exception {
        mockMvc.perform(get("/api/promises").param("sort", "invoiceId,asc").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/promises").param("filter", "invoiceId:eq:abc").with(as(admin)))
                .andExpect(status().isBadRequest());
    }
}
