package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approving a dispute's resolution is the checker step for the customer's own proposal (§5.11.8): someone with
 * the approval right in the region who approves it as it stands applies it at once. Anyone else, or a rewritten
 * change, is held above the limit with the staff member as its maker, and the dispute stays pending until a
 * second person approves; denying the dispute closes what was held. Whoever last set the customer login's
 * password could have proposed the change themselves, so their approval is never the checker step (SC-10).
 */
class DisputeApprovalGateTest extends ApprovalTestBase {

    static final String VOID = "{\"action\":\"void\"}";

    Region north;
    User approver;
    User resolver;
    Customer acme;
    User acmeLogin;
    User collector;
    Payment payment;

    @BeforeEach
    void setUp() {
        north = workRegion("NORTH");
        approver = approverIn(north);
        // Resolves disputes in NORTH, but cannot approve changes there.
        resolver = staffIn("resolver", "ADMIN", north, AccessLevel.MANAGE);
        acme = customer("Acme Ltd", north);
        acmeLogin = customerUser("acme.login", acme.getId());
        collector = collectorIn(north);
        invoice(acme, "500.00");
        actAs(admin);
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("500.00"),
                "Bank", null, null, collector.getId(), null));
        SecurityContextHolder.clearContext();
        threshold(north, ApprovalOperation.DISPUTE_APPROVE, "100.00");
    }

    @Test
    void theCustomersProposalApprovedAsItStandsByAnApproverIsApplied() throws Exception {
        Long disputeId = open(VOID);

        resolve(disputeId, approver, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.resolvedByUserId").value(approver.getId()));

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void someoneWithoutTheApprovalRightIsHeldAndASecondPersonAppliesIt() throws Exception {
        Long disputeId = open(VOID);

        JsonNode held = body(resolve(disputeId, resolver, "Refund agreed").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").doesNotExist()));

        JsonNode change = held.get("pendingChange");
        assertThat(change.get("operation").asText()).isEqualTo("DISPUTE_APPROVE");
        assertThat(change.get("entityType").asText()).isEqualTo("DISPUTE");
        assertThat(change.get("entityId").asLong()).isEqualTo(disputeId);
        assertThat(change.get("amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(disputeRepository.findById(disputeId).orElseThrow().getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
        PendingChange pc = pendingOf("DISPUTE", disputeId);
        assertThat(pc.getMakerUserId()).isEqualTo(resolver.getId());
        assertThat(pc.getAffectedType()).isEqualTo("PAYMENT");
        assertThat(pc.getAffectedId()).isEqualTo(payment.getId());
        assertThat(objectMapper.readTree(pc.getPayloadJson()).get("appliedChangeJson").asText()).isEqualTo(VOID);
        assertThat(objectMapper.readTree(pc.getProposedJson()).get("status").asText()).isEqualTo("VOIDED");
        // The rows go on the target, naming the dispute.
        assertThat(audit("PAYMENT", payment.getId(), "CHANGE_REQUESTED")).singleElement().satisfies(a -> {
            assertThat(a.getDisputeId()).isEqualTo(disputeId);
            assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
        });

        JsonNode detail = body(approve(pc.getId(), approver).andExpect(status().isOk()));

        assertThat(detail.get("status").asText()).isEqualTo("APPROVED");
        assertThat(detail.get("result").get("link").asText()).isEqualTo("/disputes/" + disputeId);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        Dispute resolved = disputeRepository.findById(disputeId).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.APPROVED);
        assertThat(resolved.getResolvedByUserId()).as("resolved by the maker").isEqualTo(resolver.getId());
        assertThat(resolved.getAdminNotes()).isEqualTo("Refund agreed");
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(audit("PAYMENT", payment.getId(), "DISPUTE_APPROVED")).singleElement().satisfies(a -> {
            assertThat(a.getChangedByUserId()).isEqualTo(resolver.getId());
            assertThat(a.getApprovedByUserId()).isEqualTo(approver.getId());
        });
    }

    @Test
    void aRewrittenChangeIsHeldEvenForAnApprover() throws Exception {
        Long disputeId = open(VOID);

        resolve(disputeId, approver, null, "{\"action\":\"update_amount\",\"amount\":100}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.amount").value(400.00));

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("500.00");
        assertThat(pendingOf("DISPUTE", disputeId).getMakerUserId()).isEqualTo(approver.getId());
        // The same change written differently is still the proposal, and applies at once.
        actAs(admin);
        Payment second = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("50.00"), "Bank", null, null, collector.getId(), null));
        SecurityContextHolder.clearContext();
        Long another = open(second, "{ \"action\" : \"void\" }");
        resolve(another, approver, null, VOID).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(paymentRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
    }

    @Test
    void denyingTheDisputeClosesTheChangeWaitingOnIt() throws Exception {
        Long disputeId = open(VOID);
        resolve(disputeId, resolver, null).andExpect(status().isAccepted());
        PendingChange pc = pendingOf("DISPUTE", disputeId);

        mockMvc.perform(post("/api/disputes/" + disputeId + "/deny").with(as(approver))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("adminNotes", "No refund"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        PendingChange closed = reload(pc);
        assertThat(closed.getStatus()).isEqualTo(PendingStatus.CLOSED);
        assertThat(closed.getDecidedByUserId()).isNull();
        assertThat(closed.getDecisionNote()).isEqualTo("Dispute denied");
        assertThat(closed.getOutstandingKey()).isNull();
        assertThat(audit("PAYMENT", payment.getId(), PendingChangeCloser.CHANGE_CLOSED)).singleElement()
                .satisfies(a -> {
                    assertThat(a.getChangedByUserId()).isNull();
                    assertThat(a.getPendingChangeId()).isEqualTo(pc.getId());
                });
        assertThat(noticesTo(resolver, ApprovalNotifier.CLOSED)).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).endsWith(": Dispute denied"));
        approve(pc.getId(), approver).andExpect(status().isConflict());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
    }

    /**
     * SC-10: the administrator who set the customer login's password could propose a change as the customer and
     * approve it themselves; it is held. Changing the password while signed in as the customer does not clear
     * them, since they could be the one who changed it; someone else with the approval right is still the
     * checker.
     */
    @Test
    void whoeverSetTheCustomersPasswordIsNotTheSecondPairOfEyes() throws Exception {
        Map<String, Object> update = new HashMap<>();
        update.put("name", "Acme Ltd");
        update.put("email", "acme.login@test.local");
        update.put("password", "set-by-admin");
        mockMvc.perform(put("/api/customers/" + acme.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk());
        assertThat(userRepository.findCredentialsSetBy(acmeLogin.getId())).contains(admin.getId());
        Long disputeId = open(VOID);

        resolve(disputeId, admin, null).andExpect(status().isAccepted());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
        PendingChange held = pendingOf("DISPUTE", disputeId);
        assertThat(held.getMakerUserId()).isEqualTo(admin.getId());

        // The administrator knows the password, so a change of it proves nothing about who made it.
        mockMvc.perform(post("/api/auth/change-password").with(as(acmeLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "set-by-admin", "newPassword", "only-i-know"))))
                .andExpect(status().isOk());
        assertThat(userRepository.findCredentialsSetBy(acmeLogin.getId())).contains(admin.getId());
        resolve(disputeId, admin, null).andExpect(status().isAccepted());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
        PendingChange again = pendingOf("DISPUTE", disputeId);
        assertThat(reload(held).getStatus()).isEqualTo(PendingStatus.SUPERSEDED);

        resolve(disputeId, approver, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        assertThat(reload(again).getStatus()).as("resolved, so what waited on it is closed")
                .isEqualTo(PendingStatus.CLOSED);
        assertThat(reload(again).getDecisionNote()).isEqualTo("Dispute resolved");
    }

    /**
     * A login from before the column was kept records nobody; the staff member who created the customer chose the
     * password it started with, so they are not the checker either (SC-10).
     */
    @Test
    void onALoginWithNoSetterRecordedWhoeverCreatedTheCustomerIsNotTheSecondPairOfEyes() throws Exception {
        assertThat(userRepository.findCredentialsSetBy(acmeLogin.getId())).isEmpty();
        auditLogRepository.save(AuditLog.builder().entityType("CUSTOMER").entityId(acme.getId())
                .action("CUSTOMER_CREATED").changedByUserId(admin.getId()).build());
        Long disputeId = open(VOID);

        resolve(disputeId, admin, null).andExpect(status().isAccepted());

        assertThat(pendingOf("DISPUTE", disputeId).getMakerUserId()).isEqualTo(admin.getId());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.ACTIVE);
        resolve(disputeId, approver, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
    }

    /**
     * New lines proposed without a price are held at the price the approver was shown (§5.11.1): a catalogue
     * change while the resolution waits does not change the invoice it re-issues.
     */
    @Test
    void aHeldReplacementOfLinesKeepsThePricesTheApproverSaw() throws Exception {
        Product gadget = product("Gadget", "100.00");
        Invoice inv = invoice(acme, "500.00");
        Long disputeId = open("INVOICE", inv.getId(), json(Map.of("action", "replace_items",
                "items", List.of(Map.of("productId", gadget.getId(), "quantity", 3)))));

        resolve(disputeId, resolver, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingChange.amount").value(200.00));
        PendingChange pc = pendingOf("DISPUTE", disputeId);
        JsonNode stored = objectMapper.readTree(objectMapper.readTree(pc.getPayloadJson())
                .get("appliedChangeJson").asText());
        assertThat(stored.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(stored.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(objectMapper.readTree(pc.getProposedJson()).get("total").decimalValue())
                .isEqualByComparingTo("300.00");
        // The price rises by less than would make the change grow.
        gadget.setPrice(new BigDecimal("150.00"));
        productRepository.save(gadget);

        approve(pc.getId(), approver).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        Invoice reissued = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(reissued.getTotal()).isEqualByComparingTo("300.00");
        assertThat(disputeRepository.findById(disputeId).orElseThrow().getStatus()).isEqualTo(DisputeStatus.APPROVED);
    }

    private Long open(String proposedChangeJson) throws Exception {
        return open(payment, proposedChangeJson);
    }

    /** The customer opens a dispute on the payment, proposing this change. */
    private Long open(Payment target, String proposedChangeJson) throws Exception {
        return open("PAYMENT", target.getId(), proposedChangeJson);
    }

    private Long open(String targetType, Long targetId, String proposedChangeJson) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("targetType", targetType);
        req.put("targetId", targetId);
        req.put("reason", "Paid twice");
        req.put("proposedChangeJson", proposedChangeJson);
        return body(mockMvc.perform(post("/api/disputes").with(as(acmeLogin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())).get("id").asLong();
    }

    private ResultActions resolve(Long disputeId, User as, String adminNotes) throws Exception {
        return resolve(disputeId, as, adminNotes, null);
    }

    private ResultActions resolve(Long disputeId, User as, String adminNotes, String appliedChangeJson)
            throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("adminNotes", adminNotes);
        req.put("appliedChangeJson", appliedChangeJson);
        return mockMvc.perform(post("/api/disputes/" + disputeId + "/approve").with(as(as))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)));
    }
}
