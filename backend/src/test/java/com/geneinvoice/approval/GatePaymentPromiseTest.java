package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gates on payments and promises (B2, §5.11.1): a payment above its region's limit moves no money until it
 * is approved; a promise made, changed or overridden above the limit is held and the promise left as it was;
 * handing a record to another POC is never held.
 */
class GatePaymentPromiseTest extends ApprovalTestBase {

    Region north;
    Region south;
    User cashier;
    User collector;
    Customer acme;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        south = workRegion("SOUTH");
        cashier = makerIn(north);
        collector = collectorIn(north);
        approverIn(north);
        acme = customer("Acme Ltd", north);
    }

    @Test
    void aHeldPaymentMovesNoMoney() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        Invoice inv = invoice(acme, "500.00");
        long payments = paymentRepository.count();
        long events = automationEventRepository.count();

        // More than is owed: applied, it would pay the invoice and leave credit behind.
        JsonNode held = body(pay(acme, "600.00", collector, cashier).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").doesNotExist()));

        JsonNode change = held.get("pendingChange");
        assertThat(change.get("operation").asText()).isEqualTo("PAYMENT_RECORD");
        assertThat(change.get("entityType").asText()).isEqualTo("PAYMENT");
        assertThat(change.get("entityId").isNull()).isTrue();
        assertThat(change.get("amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(change.get("summary").asText()).isEqualTo("Record payment of ₹600.00 from Acme Ltd");
        assertThat(paymentRepository.count()).isEqualTo(payments);
        Invoice after = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(after.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(after.getVersion()).isEqualTo(inv.getVersion());
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo("0.00");
        assertThat(automationEventRepository.count()).isEqualTo(events);

        PendingChange pc = pendingChangeRepository.findById(change.get("id").asLong()).orElseThrow();
        assertThat(pc.getRecordPocUserId()).isEqualTo(collector.getId());
        assertThat(objectMapper.readTree(pc.getPayloadJson()).get("amount").decimalValue())
                .isEqualByComparingTo("600.00");
        assertThat(audit("CUSTOMER", acme.getId(), "CHANGE_REQUESTED")).singleElement()
                .satisfies(a -> assertThat(a.getPendingChangeId()).isEqualTo(pc.getId()));

        // At or under the limit it is recorded as always.
        pay(acme, "100.00", collector, cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("100.00");
    }

    /**
     * APP-R2-1: a held change of a promise's invoices reads their numbers, and a held payment reads the promises it
     * keeps by amount and date.
     */
    @Test
    void heldChangesNameInvoicesAndPromisesNotIds() throws Exception {
        Invoice first = invoice(acme, "400.00");
        PaymentPromise p = promise(acme, "1000.00", collector);
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        Map<String, Object> put = new java.util.HashMap<>();
        put.put("amount", new BigDecimal("700.00"));
        put.put("promisedDate", p.getPromisedDate().toString());
        put.put("invoiceIds", List.of(first.getId()));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/promises/" + p.getId()).with(as(collector))
                        .contentType(MediaType.APPLICATION_JSON).content(json(put)))
                .andExpect(status().isAccepted());
        Map<String, JsonNode> update = changesOf(pendingOf("PROMISE", p.getId()).getId());
        assertThat(update.get("invoiceIds").get("label").asText()).isEqualTo("Invoices");
        assertThat(update.get("invoiceIds").get("proposedText").asText()).isEqualTo(first.getInvoiceNumber());
        assertThat(update.get("invoiceIds").get("currentText").asText()).isEqualTo("None");

        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        Map<String, Object> pay = new java.util.HashMap<>();
        pay.put("customerId", acme.getId());
        pay.put("amount", new BigDecimal("300.00"));
        pay.put("method", "Bank");
        pay.put("collectionPocUserId", collector.getId());
        pay.put("promiseIds", List.of(p.getId()));
        Long payId = body(mockMvc.perform(post("/api/payments").with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON).content(json(pay)))
                .andExpect(status().isAccepted())).get("pendingChange").get("id").asLong();
        Map<String, JsonNode> payment = changesOf(payId);
        assertThat(payment.get("promiseIds").get("label").asText()).isEqualTo("Promises");
        assertThat(payment.get("promiseIds").get("proposedText").asText()).isEqualTo("Promise of "
                + com.geneinvoice.common.Money.format(new BigDecimal("1000.00")) + " by " + p.getPromisedDate());
        assertThat(payment).as("no invoices named, nothing set").doesNotContainKey("invoiceIds");
    }

    private Map<String, JsonNode> changesOf(Long pendingChangeId) throws Exception {
        JsonNode detail = body(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/approvals/" + pendingChangeId).with(as(admin)))
                .andExpect(status().isOk()));
        Map<String, JsonNode> changes = new java.util.HashMap<>();
        detail.get("changes").forEach(c -> changes.put(c.get("field").asText(), c));
        return changes;
    }

    @Test
    void aPromiseMadeChangedOrOverriddenAboveTheLimitIsHeld() throws Exception {
        PaymentPromise changed = promise(acme, "1000.00", collector);
        PaymentPromise overridden = promise(acme, "800.00", collector);
        PaymentPromise third = promise(acme, "90.00", collector);
        PaymentPromise big = promise(acme, "500.00", collector);
        threshold(north, ApprovalOperation.PROMISE_CREATE, "100.00");
        threshold(north, ApprovalOperation.PROMISE_UPDATE, "100.00");
        threshold(north, ApprovalOperation.PROMISE_OVERRIDE, "100.00");
        long promises = promiseRepository.count();

        JsonNode made = body(mockMvc.perform(post("/api/promises").with(as(collector))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", acme.getId(), "amount", 500,
                                "promisedDate", Today.date().plusDays(5).toString(),
                                "collectionPocUserId", collector.getId()))))
                .andExpect(status().isAccepted()));
        assertThat(made.get("pendingChange").get("operation").asText()).isEqualTo("PROMISE_CREATE");
        assertThat(promiseRepository.count()).isEqualTo(promises);
        PendingChange create = pendingChangeRepository.findById(made.get("pendingChange").get("id").asLong())
                .orElseThrow();
        assertThat(objectMapper.readTree(create.getPayloadJson()).get("collectionPocUserId").asLong())
                .as("the POC it resolved to is kept").isEqualTo(collector.getId());

        // Promising 1000 → 700 gives away 300.
        putPromise(changed.getId(), "700.00", changed.getPromisedDate(), null, collector)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.operation").value("PROMISE_UPDATE"))
                .andExpect(jsonPath("$.pendingChange.amount").value(300.00));
        PaymentPromise unchanged = promiseRepository.findById(changed.getId()).orElseThrow();
        assertThat(unchanged.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(unchanged.getPromisedDate()).isEqualTo(changed.getPromisedDate());
        PendingChange update = pendingOf("PROMISE", changed.getId());
        assertThat(objectMapper.readTree(update.getBaseJson()).get("amount").decimalValue())
                .isEqualByComparingTo("1000.00");
        assertThat(objectMapper.readTree(update.getProposedJson()).get("amount").decimalValue())
                .isEqualByComparingTo("700.00");

        // Calling it kept writes off the 800 still unpaid.
        override(overridden.getId(), "KEPT", "Paid in cash", collector).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.operation").value("PROMISE_OVERRIDE"))
                .andExpect(jsonPath("$.pendingChange.amount").value(800.00));
        PaymentPromise stillOpen = promiseRepository.findById(overridden.getId()).orElseThrow();
        assertThat(stillOpen.getStatus()).isEqualTo(PromiseStatus.OPEN);
        assertThat(stillOpen.isStatusOverridden()).isFalse();
        assertThat(audit("PROMISE", overridden.getId(), "PROMISE_STATUS_OVERRIDDEN")).isEmpty();

        // Calling it broken writes nothing off; the same amount later owes it all later.
        override(third.getId(), "BROKEN", "Customer went quiet", collector).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BROKEN"));
        putPromise(third.getId(), "90.00", third.getPromisedDate().plusDays(3), null, collector)
                .andExpect(status().isOk());
        putPromise(big.getId(), "500.00", big.getPromisedDate().plusDays(3), null, collector)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.amount").value(500.00));
        assertThat(pendingCount()).isEqualTo(4);
    }

    @Test
    void handingARecordToAnotherPocIsNeverHeldEvenAtAZeroLimit() throws Exception {
        User seller2 = staffIn("seller", "SALES_POC", north, AccessLevel.MANAGE);
        User collector2 = collectorIn(north);
        Invoice inv = invoice(acme, "500.00");
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("200.00"), "Bank", null, null, collector.getId(), null));
        PaymentPromise promise = promise(acme, "300.00", collector);
        everyLimitAtZero(north);

        mockMvc.perform(patch("/api/invoices/" + inv.getId()).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("salesPocUserId", seller2.getId()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.salesPoc.id").value(seller2.getId()));
        mockMvc.perform(patch("/api/payments/" + payment.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("collectionPocUserId", collector2.getId()))))
                .andExpect(status().isOk());
        JsonNode bulk = body(mockMvc.perform(post("/api/promises/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "REASSIGN_COLLECTION_POC", "ids", List.of(promise.getId()),
                                "params", Map.of("userId", collector2.getId())))))
                .andExpect(status().isOk()));
        assertThat(bulk.get("succeeded")).extracting(JsonNode::asLong).containsExactly(promise.getId());
        JsonNode invoiceBulk = body(mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "REASSIGN_SALES_POC", "ids", List.of(inv.getId()),
                                "params", Map.of("userId", seller.getId())))))
                .andExpect(status().isOk()));
        assertThat(invoiceBulk.get("succeeded")).extracting(JsonNode::asLong).containsExactly(inv.getId());
        assertThat(invoiceBulk.get("pending")).isEmpty();

        assertThat(pendingChangeRepository.count()).isZero();
        assertThat(promiseRepository.findById(promise.getId()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(collector2.getId());
    }

    /** PC-13: each region's own limit decides, not the strictest anywhere. */
    @Test
    void theLimitIsTheOneOfThePaymentsOwnRegion() throws Exception {
        threshold(north, ApprovalOperation.PAYMENT_RECORD, "100.00");
        threshold(south, ApprovalOperation.PAYMENT_RECORD, "10000.00");
        User both = collectorIn(north);
        grant(both, south, AccessLevel.MANAGE, RecordScope.ALL);
        Customer southwind = customer("Southwind", south);

        pay(acme, "500.00", both, both).andExpect(status().isAccepted());
        pay(southwind, "500.00", both, both).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(pendingChangeRepository.findAll()).singleElement()
                .satisfies(pc -> assertThat(pc.getRegionId()).isEqualTo(north.getId()));
        assertThat(paymentRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getCustomer().getId()).isEqualTo(southwind.getId()));
    }
}
