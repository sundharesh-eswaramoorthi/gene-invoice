package com.geneinvoice.common;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InputValidationTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepository;

    User admin;
    User collector;
    User customerLogin;
    Customer acme;
    Product widget;
    Invoice invoice;
    Payment payment;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        User sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        customerLogin = customerUser("acme.login", acme.getId());
        actAs(admin);
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("40.00"), "Cash", null, null, collector.getId(), null));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                               Object body) throws Exception {
        return mockMvc.perform(req.with(as(admin)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void overlongTextIsAFieldErrorNotAFailedInsert() throws Exception {
        send(patch("/api/invoices/" + invoice.getId()), Map.of("notes", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.notes").value("must be at most 500 characters"));
        send(patch("/api/invoices/" + invoice.getId()), Map.of("notes", "x".repeat(500)))
                .andExpect(status().isOk());
        send(patch("/api/payments/" + payment.getId()), Map.of("notes", "x".repeat(301)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.notes").value("must be at most 300 characters"));
        send(post("/api/payments"), Map.of("customerId", acme.getId(), "amount", 10,
                "method", "m".repeat(41), "collectionPocUserId", collector.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.method").exists());
    }

    @Test
    void aDisputeReasonUpToItsLimitIsSavedThoughTheAdminNotificationHoldsLess() throws Exception {
        String reason = "r".repeat(FieldLimits.DISPUTE_TEXT - 4) + "-END";
        String body = mockMvc.perform(post("/api/disputes").with(as(customerLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetType", "INVOICE", "targetId", invoice.getId(), "reason", reason))))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        assertThat(disputeRepository.findById(id).orElseThrow().getReason()).isEqualTo(reason);
        Notification toAdmin = notificationRepository.findByUserIdOrderByCreatedAtDesc(admin.getId()).stream()
                .filter(n -> n.getLink() != null && n.getLink().endsWith("/" + id))
                .findFirst().orElseThrow();
        assertThat(toAdmin.getMessage()).hasSize(Notification.MESSAGE_MAX).endsWith("…");
        assertThat(toAdmin.getLink()).isEqualTo("/disputes/" + id);
    }

    @Test
    void aProductTakenOutOfTheCatalogueCannotGoOnANewInvoice() throws Exception {
        Product retired = product("Retired widget", "50.00");
        retired.setActive(false);
        productRepository.save(retired);

        send(post("/api/invoices"), Map.of("customerId", acme.getId(),
                        "salesPocUserId", admin.getId(),
                        "items", List.of(Map.of("productId", retired.getId(), "quantity", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Retired widget is no longer an active product"));
    }

    @Test
    void anUnknownInvoiceIdOnAPaymentIsRefusedInsteadOfBecomingCredit() throws Exception {
        long before = paymentRepository.count();

        send(post("/api/payments"), Map.of("customerId", acme.getId(), "amount", 25,
                        "collectionPocUserId", collector.getId(),
                        "invoiceIds", List.of(invoice.getId(), 99999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("Invoice not found")));

        assertThat(paymentRepository.count()).isEqualTo(before);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void paymentAmountsMustBeWholeCents() throws Exception {
        for (String amount : List.of("0.001", "10.555")) {
            send(post("/api/payments"), Map.of("customerId", acme.getId(), "amount", new BigDecimal(amount),
                    "collectionPocUserId", collector.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.amount").value("must have at most 2 decimal places"));
        }
        send(post("/api/payments"), Map.of("customerId", acme.getId(), "amount", new BigDecimal("10.55"),
                "collectionPocUserId", collector.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10.55));
    }

    @Test
    void promiseAmountsMustBeWholeCents() throws Exception {
        send(post("/api/promises"), Map.of("customerId", acme.getId(), "amount", new BigDecimal("10.555"),
                "promisedDate", LocalDate.now().plusDays(3).toString(),
                "collectionPocUserId", collector.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").value("must have at most 2 decimal places"));
    }

    private Dispute openDispute(DisputeTargetType type, Long targetId) {
        actAs(customerLogin);
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(type, targetId, "wrong", null));
        actAs(admin);
        return d;
    }

    private ResultActions approve(Dispute d, String change) throws Exception {
        return send(post("/api/disputes/" + d.getId() + "/approve"), Map.of("appliedChangeJson", change));
    }

    @Test
    void anInvoiceDisputeCannotSetABadQuantityProductOrPrice() throws Exception {
        Dispute d = openDispute(DisputeTargetType.INVOICE, invoice.getId());
        Map<String, String> refused = Map.of(
                "{\"action\":\"replace_items\",\"items\":[{\"productId\":" + widget.getId() + ",\"quantity\":0}]}",
                "Quantity must be positive",
                "{\"action\":\"replace_items\",\"items\":[{\"productId\":" + widget.getId() + ",\"quantity\":-2}]}",
                "Quantity must be positive",
                "{\"action\":\"replace_items\",\"items\":[{\"quantity\":1}]}",
                "productId must be a whole number",
                "{\"action\":\"replace_items\",\"items\":[{\"productId\":" + widget.getId()
                        + ",\"quantity\":1,\"unitPrice\":\"abc\"}]}",
                "unitPrice must be a number",
                "{bad", "The change is not valid JSON");
        for (Map.Entry<String, String> attempt : refused.entrySet()) {
            approve(d, attempt.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(attempt.getValue()));
        }
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getTotal()).isEqualByComparingTo("100.00");

        approve(d, "{\"action\":\"replace_items\",\"items\":[{\"productId\":" + widget.getId()
                + ",\"quantity\":1,\"unitPrice\":0}]}")
                .andExpect(status().isOk());
    }

    @Test
    void aPaymentDisputeCannotSetANonNumericOrSubCentAmount() throws Exception {
        Dispute d = openDispute(DisputeTargetType.PAYMENT, payment.getId());
        approve(d, "{\"action\":\"update_amount\",\"amount\":\"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("amount must be a number"));
        approve(d, "{\"action\":\"update_amount\",\"amount\":0.001}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Amount must have at most 2 decimal places"));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("40.00");
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.PENDING);
    }
}
