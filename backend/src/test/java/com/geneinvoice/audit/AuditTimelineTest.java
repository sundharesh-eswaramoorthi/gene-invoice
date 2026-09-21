package com.geneinvoice.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The History tab: a customer's and an invoice's timeline cover the records hanging off them. */
class AuditTimelineTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired AuditLogRepository auditLogRepository;

    User admin;
    User collections;
    User acmeLogin;
    Customer acme;
    Product widget;

    static final LocalDate TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1);

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        acmeLogin = customerUser("acme.login", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    private Invoice invoice(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private Payment pay(String amount, List<Long> invoiceIds) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal(amount), "Cash", null, invoiceIds,
                collections.getId(), null));
    }

    private void promise(List<Long> invoiceIds) {
        promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("50.00"), TOMORROW, null, "will pay", invoiceIds, null));
    }

    /** Opened by the customer's own login, as the dispute flow requires; returns the dispute id. */
    private Long dispute(Long invoiceId, String reason, String proposedChangeJson) {
        actAs(acmeLogin);
        Long id = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, invoiceId, reason, proposedChangeJson)).getId();
        actAs(admin);
        return id;
    }

    private List<JsonNode> timeline(String type, Long id, boolean related, User caller) throws Exception {
        String body = mockMvc.perform(get("/api/audit")
                        .param("entityType", type)
                        .param("entityId", id.toString())
                        .param("includeRelated", String.valueOf(related))
                        .with(as(caller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> rows = new ArrayList<>();
        objectMapper.readTree(body).forEach(rows::add);
        return rows;
    }

    private static List<String> events(List<JsonNode> rows) {
        return rows.stream()
                .map(r -> r.get("entityType").asText() + ":" + r.get("action").asText())
                .toList();
    }

    private static JsonNode first(List<JsonNode> rows, String action) {
        return rows.stream().filter(r -> r.get("action").asText().equals(action))
                .findFirst().orElseThrow();
    }

    private static List<JsonNode> derivedOnly(List<JsonNode> rows) {
        return rows.stream().filter(r -> r.get("derived").asBoolean()).toList();
    }

    @Test
    void aCustomersHistoryCoversItsInvoicesPaymentsPromisesAndDisputes() throws Exception {
        Invoice inv = invoice(acme);
        pay("60.00", null);
        promise(List.of(inv.getId()));
        dispute(inv.getId(), "Wrong quantity", null);
        Invoice elsewhere = invoice(customer("Other Ltd"));

        List<JsonNode> rows = timeline("CUSTOMER", acme.getId(), true, admin);

        assertThat(events(rows)).contains(
                "CUSTOMER:POC_ASSIGNED", "INVOICE:INVOICE_CREATED", "PAYMENT:PAYMENT_RECORDED",
                "INVOICE:PAYMENT_APPLIED", "PROMISE:PROMISE_CREATED", "DISPUTE:DISPUTE_OPENED");
        assertThat(rows).noneMatch(r -> r.get("entityType").asText().equals("INVOICE")
                && r.get("entityId").asLong() == elsewhere.getId());
        assertThat(rows).filteredOn(r -> r.get("entityType").asText().equals("INVOICE"))
                .allMatch(r -> r.get("entityLabel").asText().equals(inv.getInvoiceNumber()));

        List<Instant> times = rows.stream()
                .map(r -> Instant.parse(r.get("createdAt").asText())).toList();
        assertThat(times).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void anInvoicesHistoryCoversOnlyThePaymentsPromisesAndDisputesOnThatInvoice() throws Exception {
        Invoice paid = invoice(acme);
        Invoice promised = invoice(acme);
        pay("100.00", List.of(paid.getId()));
        promise(List.of(promised.getId()));
        dispute(promised.getId(), "Wrong quantity", null);

        List<JsonNode> paidRows = timeline("INVOICE", paid.getId(), true, admin);
        assertThat(events(paidRows))
                .contains("INVOICE:INVOICE_CREATED", "INVOICE:PAYMENT_APPLIED")
                .doesNotContain("PROMISE:PROMISE_CREATED", "DISPUTE:DISPUTE_OPENED");
        JsonNode after = objectMapper.readTree(first(paidRows, "PAYMENT_APPLIED").get("afterJson").asText());
        assertThat(after.get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.get("status").asText()).isEqualTo("FULLY_PAID");

        assertThat(events(timeline("INVOICE", promised.getId(), true, admin)))
                .contains("INVOICE:INVOICE_CREATED", "PROMISE:PROMISE_CREATED", "DISPUTE:DISPUTE_OPENED")
                .doesNotContain("INVOICE:PAYMENT_APPLIED");
    }

    @Test
    void voidingAPaymentShowsTheReversalOnTheInvoiceItHadPaid() throws Exception {
        Invoice inv = invoice(acme);
        Payment p = pay("40.00", null);
        paymentService.voidPayment(p.getId());

        JsonNode reversed = first(timeline("INVOICE", inv.getId(), true, admin), "PAYMENT_REVERSED");
        JsonNode before = objectMapper.readTree(reversed.get("beforeJson").asText());
        JsonNode after = objectMapper.readTree(reversed.get("afterJson").asText());
        assertThat(before.get("paidAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(after.get("paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(after.get("paymentId").asLong()).isEqualTo(p.getId());
    }

    @Test
    void aDisputeReasonLongerThanTheHistoryColumnStillOpensAndDeniesTheDispute() throws Exception {
        Invoice inv = invoice(acme);
        String reason = "x".repeat(600);
        Long disputeId = dispute(inv.getId(), reason, null);
        disputeService.deny(disputeId, new DisputeDtos.ResolveDisputeRequest("y".repeat(600), null));

        List<JsonNode> rows = timeline("INVOICE", inv.getId(), true, admin);
        JsonNode opened = first(rows, "DISPUTE_OPENED");
        assertThat(opened.get("reason").asText()).hasSizeLessThanOrEqualTo(AuditService.REASON_MAX);
        // The full text is still in the snapshot.
        assertThat(objectMapper.readTree(opened.get("afterJson").asText()).get("reason").asText())
                .isEqualTo(reason);
        assertThat(first(rows, "DISPUTE_DENIED").get("reason").asText())
                .hasSizeLessThanOrEqualTo(AuditService.REASON_MAX);
    }

    @Test
    void recordsOlderThanTheirAuditTrailGetTheirEventsDerived() throws Exception {
        Invoice inv = invoice(acme);
        Payment p = pay("30.00", null);
        // Simulate rows written before these events were audited.
        auditLogRepository.deleteAll(auditLogRepository.findAll().stream()
                .filter(a -> (a.getEntityType().equals("INVOICE") && a.getEntityId().equals(inv.getId()))
                        || (a.getEntityType().equals("PAYMENT") && a.getEntityId().equals(p.getId())))
                .toList());

        List<JsonNode> derived = derivedOnly(timeline("CUSTOMER", acme.getId(), true, admin));
        assertThat(events(derived)).contains(
                "CUSTOMER:CUSTOMER_CREATED", "INVOICE:INVOICE_CREATED", "PAYMENT:PAYMENT_RECORDED",
                "INVOICE:PAYMENT_APPLIED");
        assertThat(derived).allMatch(r -> r.get("id").isNull());
        // A payment's later state is not passed off as how it was recorded.
        JsonNode recorded = objectMapper.readTree(first(derived, "PAYMENT_RECORDED").get("afterJson").asText());
        assertThat(recorded.has("status")).isFalse();
        assertThat(recorded.get("amount").decimalValue()).isEqualByComparingTo("30.00");

        assertThat(events(derivedOnly(timeline("INVOICE", inv.getId(), true, admin))))
                .contains("INVOICE:INVOICE_CREATED", "INVOICE:PAYMENT_APPLIED");
    }

    @Test
    void aDerivedCreationShowsTheOriginalTotalRatherThanOneADisputeChangedLater() throws Exception {
        Invoice inv = invoice(acme);
        Long disputeId = dispute(inv.getId(), "Should be three",
                "{\"action\":\"replace_items\",\"items\":[{\"productId\":" + widget.getId() + ",\"quantity\":3}]}");
        disputeService.approve(disputeId, new DisputeDtos.ResolveDisputeRequest("Agreed", null));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getTotal())
                .isEqualByComparingTo("300.00");
        auditLogRepository.deleteAll(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", inv.getId()).stream()
                .filter(a -> a.getAction().equals("INVOICE_CREATED"))
                .toList());

        JsonNode created = first(timeline("INVOICE", inv.getId(), true, admin), "INVOICE_CREATED");
        assertThat(created.get("derived").asBoolean()).isTrue();
        assertThat(objectMapper.readTree(created.get("afterJson").asText()).get("total").decimalValue())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void anEventAlreadyAuditedIsNeverDerivedASecondTime() throws Exception {
        Invoice inv = invoice(acme);
        pay("30.00", null);

        List<JsonNode> rows = timeline("INVOICE", inv.getId(), true, admin);
        assertThat(events(rows)).containsOnlyOnce("INVOICE:INVOICE_CREATED", "INVOICE:PAYMENT_APPLIED");
        assertThat(rows).noneMatch(r -> r.get("derived").asBoolean());

        // The customer itself was created straight through the repository, so only that is derived.
        assertThat(events(derivedOnly(timeline("CUSTOMER", acme.getId(), true, admin))))
                .containsExactly("CUSTOMER:CUSTOMER_CREATED");
    }

    @Test
    void theCustomerLoginSeesItsHistoryWithoutAnyPocIdentity() throws Exception {
        User sales = user("sam.sales", "SALES_POC");
        actAs(sales);
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        actAs(collections);
        pay("60.00", null);
        promise(List.of(inv.getId()));
        actAs(admin);
        invoiceService.reassignSalesPoc(inv.getId(), admin.getId());
        dispute(inv.getId(), "Wrong quantity", null);

        List<JsonNode> rows = timeline("CUSTOMER", acme.getId(), true, acmeLogin);
        assertThat(events(rows))
                .contains("INVOICE:INVOICE_CREATED", "PAYMENT:PAYMENT_RECORDED",
                        "PROMISE:PROMISE_CREATED", "DISPUTE:DISPUTE_OPENED")
                .noneMatch(e -> e.contains("POC"))
                // The reassignment changed nothing but the POC, so there is nothing left to show.
                .doesNotContain("INVOICE:INVOICE_UPDATED");
        Set<Long> staff = Set.of(sales.getId(), collections.getId(), admin.getId());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.path("beforeJson").asText("").toLowerCase(Locale.ROOT)).doesNotContain("poc");
            assertThat(r.path("afterJson").asText("").toLowerCase(Locale.ROOT)).doesNotContain("poc");
            // Whoever raised the invoice or took the payment is the account's POC: never named.
            assertThat(r.path("changedByUsername").asText("acme.login")).isEqualTo("acme.login");
            assertThat(r.get("changedByUserId").isNull()
                    || !staff.contains(r.get("changedByUserId").asLong())).isTrue();
        });
        assertThat(first(rows, "INVOICE_CREATED").get("actorHidden").asBoolean()).isTrue();
        assertThat(first(rows, "DISPUTE_OPENED").get("changedByUsername").asText()).isEqualTo("acme.login");
        assertThat(first(rows, "DISPUTE_OPENED").get("actorHidden").asBoolean()).isFalse();

        List<JsonNode> staffView = timeline("CUSTOMER", acme.getId(), true, admin);
        assertThat(events(staffView)).contains("CUSTOMER:POC_ASSIGNED", "INVOICE:INVOICE_UPDATED");
        assertThat(first(staffView, "INVOICE_CREATED").get("changedByUsername").asText()).isEqualTo("sam.sales");
    }

    @Test
    void withoutIncludeRelatedTheHistoryStaysOnTheRecordItself() throws Exception {
        invoice(acme);
        assertThat(timeline("CUSTOMER", acme.getId(), false, admin))
                .isNotEmpty()
                .allMatch(r -> r.get("entityType").asText().equals("CUSTOMER"));
    }
}
