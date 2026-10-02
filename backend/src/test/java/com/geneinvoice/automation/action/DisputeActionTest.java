package com.geneinvoice.automation.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingStatus;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.RecordAssignee;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CREATE_DISPUTE as the consumer carries it out (§5.10.5, §5.10.11): an internal dispute on the record, opened as the
 * rule's owner with its assignees and the record's region, one at a time per record; the change it proposes is made
 * when someone else approves it, never by the owner, who may still deny it; a dispute on a customer is acknowledged
 * and changes nothing; and none of it, nor a change held from it, ever reaches the customer's own login (SC-1).
 */
class DisputeActionTest extends AutomationTestBase {

    /**
     * The statements one dispute may run from the lock to its commit: 21 when whom to tell was worked out before it,
     * 30 when it is worked out inside, as it once was.
     */
    static final long LOCK_MAX = 25;

    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    User admin;
    /** Owns the rules: may run them and raise disputes, but may not approve changes. */
    User owen;
    /** Resolves disputes without the right to approve changes: what they approve above the limit is held. */
    User cleo;
    /** A person who works only in another region, so cannot see the records here. */
    User nora;
    Customer acme;
    User acmeLogin;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        roleWith("DISPUTE_RULER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                Privileges.SCOPE_OVERRIDE, Privileges.NOTIFICATION_VIEW, Privileges.AUDIT_VIEW, Privileges.TASK_VIEW,
                Privileges.TASK_MANAGE);
        owen = user("owen.owner", "DISPUTE_RULER");
        cleo = user("cleo.clerk", "DISPUTE_RULER");
        nora = staff("nora.north", "ADMIN", region("NORTH"), AccessLevel.MANAGE, RecordScope.ALL);
        acme = customer("Acme Ltd");
        acmeLogin = customerUser("acme.login", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
        invoiceId = invoice(acme, widget, 2, null);
    }

    private static Map<String, Object> dispute(String reason, String proposal, Object... assignees) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", UUID.randomUUID().toString());
        a.put("type", "CREATE_DISPUTE");
        a.put("repeat", "ONCE_PER_RECORD");
        a.put("reason", reason);
        a.put("proposedChange", proposal);
        a.put("assignees", List.of(assignees));
        return a;
    }

    private Long invoiceRule(Object... actions) throws Exception {
        return saveRule(owen, rule("Review", "INVOICE", "ENTITY_UPDATED", null, actions));
    }

    private void touch(String notes) {
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    /** The rule's dispute on the invoice, opened by the consumer. */
    private Dispute raised(Long ruleId) {
        return disputeRepository.findAll().stream().filter(d -> ruleId.equals(d.getRuleId())).findFirst()
                .orElseThrow();
    }

    private List<AutomationEffect> effectsOf(Long ruleId) {
        return automationEffectRepository.findAll().stream().filter(e -> e.getRuleId().equals(ruleId))
                .sorted(Comparator.comparing(AutomationEffect::getId)).toList();
    }

    private ResultActions approve(Long disputeId, User as, Object body) throws Exception {
        return send(post("/api/disputes/" + disputeId + "/approve"), as, body);
    }

    private ResultActions read(String path, User as) throws Exception {
        return mockMvc.perform(get(path).with(as(as)));
    }

    private void limit(ApprovalOperation op, String amount) {
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(defaultRegion()).operation(op)
                .amount(new BigDecimal(amount)).windowHours(0).updatedByUserId(admin.getId())
                .updatedAt(AppClock.now()).build());
    }

    private List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(u.getId()) && n.getType().equals(type)).toList();
    }

    @Test
    void aRuleOpensAnInternalDisputeAsItsOwnerWithItsAssigneesInTheRecordsRegion() throws Exception {
        Long ruleId = invoiceRule(dispute("Review {{Invoice.Number}}", "cancel",
                userToken(admin), userToken(nora), roleToken("SALES_POC", "RECORD")));
        touch("first");
        drain();

        Dispute d = raised(ruleId);
        String number = invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();
        assertThat(d.isInternal()).isTrue();
        assertThat(d.getOrigin()).isEqualTo(Dispute.ORIGIN_AUTOMATION);
        assertThat(d.getOpenedByUserId()).isEqualTo(owen.getId());
        assertThat(d.getTargetType()).isEqualTo(DisputeTargetType.INVOICE);
        assertThat(d.getTargetId()).isEqualTo(invoiceId);
        assertThat(d.getCustomerId()).isEqualTo(acme.getId());
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(d.getReason()).isEqualTo("Review " + number);
        assertThat(d.getProposedChangeJson()).isEqualTo("{\"action\":\"cancel\"}");
        assertThat(disputeRepository.findById(d.getId()).map(x -> x.getRegion().getId()).orElseThrow())
                .isEqualTo(defaultRegion().getId());

        assertThat(recordAssigneeRepository.findAll()).filteredOn(r -> r.getOwnerType().equals(AssignedTo.DISPUTE)
                        && r.getOwnerId().equals(d.getId()) && r.getRemovedAt() == null)
                .extracting(r -> RecordAssignee.USER.equals(r.getKind()) ? "USER:" + r.getUserId() : r.getRole())
                .as("the person who cannot see the invoice is left out")
                .containsExactly("USER:" + admin.getId(), "SALES_POC");
        AutomationEffect effect = effectsOf(ruleId).get(0);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.getResultType()).isEqualTo("DISPUTE");
        assertThat(effect.getResultId()).isEqualTo(d.getId());
        assertThat(effect.warningList()).containsExactly("NORA.NORTH cannot see this record, so was not assigned");
        assertThat(noticesTo(admin, "DISPUTE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(seller, "DISPUTE_ASSIGNED")).as("the invoice's Sales POC").hasSize(1);
        assertThat(noticesTo(nora, "DISPUTE_ASSIGNED")).isEmpty();
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("DISPUTE", d.getId()))
                .extracting(AuditLog::getAction, AuditLog::getChangedByUserId, AuditLog::getOrigin, AuditLog::getRuleId)
                .containsExactly(tuple("DISPUTE_OPENED", owen.getId(), "AUTOMATION", ruleId));

        JsonNode detail = body(read("/api/disputes/" + d.getId(), admin));
        assertThat(detail.get("internal").asBoolean()).isTrue();
        assertThat(detail.get("origin").asText()).isEqualTo("AUTOMATION");
        assertThat(detail.get("ruleId").asLong()).isEqualTo(ruleId);
        assertThat(detail.get("assignees")).extracting(a -> a.get("token").get("type").asText())
                .containsExactly("USER", "ROLE");
        assertThat(detail.get("assignees").get(0).get("people").get(0).get("userId").asLong())
                .isEqualTo(admin.getId());
        assertThat(detail.get("assignees").get(1).get("people").get(0).get("userId").asLong())
                .isEqualTo(seller.getId());
    }

    /** Whom to tell is worked out before the lock (§5.10.6): inside it, only the write and its notices. */
    @Test
    void insideTheLockWindowThereIsOnlyTheWriteAndItsNotices() throws Exception {
        User helper = user("hana.helper", "DISPUTE_RULER");
        Long ruleId = invoiceRule(dispute("Review {{Invoice.Number}}", "none", userToken(admin), userToken(helper),
                roleToken("SALES_POC", "RECORD")));
        touch("first");
        automationFaults.countLockWindows(entityManagerFactory.unwrap(SessionFactory.class));
        drain();

        Dispute d = raised(ruleId);
        assertThat(noticesTo(admin, "DISPUTE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(helper, "DISPUTE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(seller, "DISPUTE_ASSIGNED")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/disputes/" + d.getId()));
        assertThat(automationFaults.lockWindows()).singleElement()
                .satisfies(n -> assertThat(n).as("statements from the lock to the commit").isBetween(1L, LOCK_MAX));
    }

    @Test
    void aSecondInternalDisputeWhileOneWaitsOnTheRecordIsSkipped() throws Exception {
        Map<String, Object> each = dispute("Review", "none");
        each.put("repeat", "EVERY_TIME");
        Long ruleId = invoiceRule(each);
        touch("first");
        drain();
        touch("second");
        drain();

        assertThat(disputeRepository.findAll()).hasSize(1);
        List<AutomationEffect> effects = effectsOf(ruleId);
        assertThat(effects).extracting(AutomationEffect::getStatus)
                .containsExactly(EffectStatus.DONE, EffectStatus.SKIPPED);
        assertThat(effects.get(1).getMessage()).isEqualTo("An internal dispute is already open on this record");

        // Once decided, the next change raises a new one.
        assertThat(send(post("/api/disputes/" + raised(ruleId).getId() + "/deny"), admin, Map.of())
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        touch("third");
        drain();
        assertThat(disputeRepository.findAll()).hasSize(2);
    }

    @Test
    void theChangeItProposesIsMadeWhenSomeoneElseApprovesIt() throws Exception {
        Long ruleId = invoiceRule(dispute("Review", "cancel"));
        touch("first");
        drain();
        Dispute d = raised(ruleId);

        // A second pair of eyes with the approval right: made at once.
        assertThat(approve(d.getId(), admin, Map.of()).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        Dispute decided = disputeRepository.findById(d.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(DisputeStatus.APPROVED);
        assertThat(decided.getResolvedByUserId()).isEqualTo(admin.getId());
    }

    @Test
    void theOwnerCanNeitherMakeNorApproveWhatTheirRuleProposedButMayDenyIt() throws Exception {
        limit(ApprovalOperation.DISPUTE_APPROVE, "1.00");
        Long ruleId = invoiceRule(dispute("Review", "cancel"));
        touch("first");
        drain();
        Dispute d = raised(ruleId);

        ResultActions own = approve(d.getId(), owen, Map.of());
        assertThat(own.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(own).get("message").asText())
                .isEqualTo("A change your own rule proposed must be approved by someone else");
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);

        // Someone without the approval right approves it above the limit: held, as internal work.
        ResultActions heldReply = approve(d.getId(), cleo, Map.of());
        assertThat(heldReply.andReturn().getResponse().getStatus()).isEqualTo(202);
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("DISPUTE:" + d.getId()).orElseThrow();
        assertThat(pc.isInternalSource()).isTrue();
        assertThat(pc.getMakerUserId()).isEqualTo(cleo.getId());

        // Nor may the owner be the second pair of eyes on it (check 5a), even holding the right to approve.
        grant(owen, defaultRegion(), AccessLevel.APPROVE, RecordScope.ALL);
        roleWith("DISPUTE_RULER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                Privileges.SCOPE_OVERRIDE, Privileges.NOTIFICATION_VIEW, Privileges.AUDIT_VIEW, Privileges.TASK_VIEW,
                Privileges.TASK_MANAGE, Privileges.CHANGE_APPROVE);
        ResultActions ownCheck = send(post("/api/approvals/" + pc.getId() + "/approve"), owen, Map.of());
        assertThat(ownCheck.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(ownCheck).get("message").asText())
                .isEqualTo("A change your own rule proposed must be approved by someone else");
        assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.PENDING);

        // The owner may still turn the dispute down; that closes what waited on it.
        assertThat(send(post("/api/disputes/" + d.getId() + "/deny"), owen, Map.of())
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.DENIED);
        assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.CLOSED);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void aHeldChangeFromItIsMadeWhenAnApproverOtherThanTheOwnerApprovesIt() throws Exception {
        limit(ApprovalOperation.DISPUTE_APPROVE, "1.00");
        Long ruleId = invoiceRule(dispute("Review", "cancel"));
        touch("first");
        drain();
        Dispute d = raised(ruleId);
        assertThat(approve(d.getId(), cleo, Map.of()).andReturn().getResponse().getStatus()).isEqualTo(202);
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("DISPUTE:" + d.getId()).orElseThrow();

        assertThat(send(post("/api/approvals/" + pc.getId() + "/approve"), admin, Map.of())
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.APPROVED);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.APPROVED);
    }

    @Test
    void aDisputeOnTheCustomerIsAcknowledgedWithoutAChange() throws Exception {
        Long ruleId = saveRule(owen, rule("Account review", "CUSTOMER", "MANUAL", null,
                dispute("Look over {{Customer.Name}}", "none")));
        assertThat(send(post(RULES + "/" + ruleId + "/run"), owen, Map.of("recordIds", List.of(acme.getId())))
                .andReturn().getResponse().getStatus()).isEqualTo(202);
        drain();

        Dispute d = raised(ruleId);
        assertThat(d.getTargetType()).isEqualTo(DisputeTargetType.CUSTOMER);
        assertThat(d.getTargetId()).isEqualTo(acme.getId());
        assertThat(d.getReason()).isEqualTo("Look over Acme Ltd");
        JsonNode detail = body(read("/api/disputes/" + d.getId(), admin));
        assertThat(detail.get("targetType").asText()).isEqualTo("CUSTOMER");
        assertThat(detail.get("targetSummary").asText()).isEqualTo("Customer Acme Ltd");

        ResultActions cancel = approve(d.getId(), admin,
                Map.of("appliedChangeJson", "{\"action\":\"cancel\"}"));
        assertThat(cancel.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(cancel).get("message").asText()).isEqualTo("Only 'none' is allowed on a customer dispute");

        Customer before = customerRepository.findById(acme.getId()).orElseThrow();
        assertThat(approve(d.getId(), admin, Map.of()).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(disputeRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DisputeStatus.APPROVED);
        Customer after = customerRepository.findById(acme.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getCreditBalance()).isEqualByComparingTo(before.getCreditBalance());
        AuditLog approved = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", acme.getId())
                .stream().filter(a -> a.getAction().equals("DISPUTE_APPROVED")).findFirst().orElseThrow();
        assertThat(approved.getBeforeJson()).isEqualTo(approved.getAfterJson());
        assertThat(approved.getDisputeId()).isEqualTo(d.getId());
    }

    @Test
    void internalWorkNeverReachesTheCustomersOwnLogin() throws Exception {
        limit(ApprovalOperation.DISPUTE_APPROVE, "1.00");
        Long ruleId = invoiceRule(dispute("Staff only: {{Invoice.Number}}", "cancel"),
                task("Look at {{Invoice.Number}}"));
        touch("first");
        drain();
        Dispute d = raised(ruleId);
        assertThat(approve(d.getId(), cleo, Map.of()).andReturn().getResponse().getStatus()).isEqualTo(202);
        assertThat(taskRepository.findAll()).hasSize(1);

        // The list, the dispute itself, and the email composer's context.
        JsonNode list = body(read("/api/disputes", acmeLogin));
        assertThat(list.get("totalElements").asLong()).isZero();
        assertThat(read("/api/disputes/" + d.getId(), acmeLogin).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(read("/api/emails/context?entityType=DISPUTE&entityId=" + d.getId(), acmeLogin)
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(body(read("/api/disputes", admin)).get("totalElements").asLong()).isEqualTo(1);

        // The invoice's trail with everything related: no dispute, task or change of the internal kind.
        JsonNode trail = body(read("/api/audit?entityType=INVOICE&entityId=" + invoiceId + "&includeRelated=true",
                acmeLogin));
        List<JsonNode> rows = StreamSupport.stream(trail.spliterator(), false).toList();
        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(r -> r.get("entityType").asText()).doesNotContain("DISPUTE", "TASK");
        assertThat(rows).extracting(r -> r.get("action").asText()).noneMatch(a -> a.startsWith("CHANGE_"));
        assertThat(rows).noneMatch(r -> r.hasNonNull("disputeId") && r.get("disputeId").asLong() == d.getId());
        JsonNode staffTrail = body(read("/api/audit?entityType=INVOICE&entityId=" + invoiceId
                + "&includeRelated=true", admin));
        assertThat(StreamSupport.stream(staffTrail.spliterator(), false))
                .extracting(r -> r.get("entityType").asText()).contains("DISPUTE", "TASK");
        assertThat(StreamSupport.stream(staffTrail.spliterator(), false))
                .extracting(r -> r.get("action").asText()).contains("CHANGE_REQUESTED");

        // Once the held change is made, the customer sees what happened to the invoice, not why.
        PendingChange pc = pendingChangeRepository.findByOutstandingKey("DISPUTE:" + d.getId()).orElseThrow();
        assertThat(send(post("/api/approvals/" + pc.getId() + "/approve"), admin, Map.of())
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        List<JsonNode> after = StreamSupport.stream(body(read("/api/audit?entityType=INVOICE&entityId=" + invoiceId
                + "&includeRelated=true", acmeLogin)).spliterator(), false).toList();
        assertThat(after).extracting(r -> r.get("action").asText()).contains("DISPUTE_APPROVED")
                .noneMatch(a -> a.startsWith("CHANGE_"));
        assertThat(after).filteredOn(r -> r.get("action").asText().equals("DISPUTE_APPROVED")).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("disputeId").isNull()).isTrue();
                    assertThat(r.get("reason").isNull()).isTrue();
                });
        assertThat(after).noneMatch(r -> r.hasNonNull("disputeId") && r.get("disputeId").asLong() == d.getId());
    }

    @Test
    void theCustomerOpensTheirOwnDisputeWhateverWaitsInternallyButNeverOneOnTheCustomer() throws Exception {
        Long ruleId = invoiceRule(dispute("Staff only", "cancel"));
        touch("first");
        drain();
        assertThat(raised(ruleId).getStatus()).isEqualTo(DisputeStatus.PENDING);

        ResultActions own = send(post("/api/disputes"), acmeLogin, Map.of("targetType", "INVOICE",
                "targetId", invoiceId, "reason", "The price is wrong"));
        assertThat(own.andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(body(own).has("internal")).as("nothing about internal work, not even that there is none").isFalse();
        assertThat(disputeRepository.findAll()).hasSize(2);
        assertThat(body(read("/api/disputes", acmeLogin)).get("totalElements").asLong()).isEqualTo(1);

        ResultActions onCustomer = send(post("/api/disputes"), acmeLogin, Map.of("targetType", "CUSTOMER",
                "targetId", acme.getId(), "reason", "Everything"));
        assertThat(onCustomer.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(onCustomer).get("message").asText())
                .isEqualTo("A dispute is raised on an invoice or a payment");
        assertThat(disputeRepository.findAll()).hasSize(2);
    }
}
