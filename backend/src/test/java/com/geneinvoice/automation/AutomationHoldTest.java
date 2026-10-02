package com.geneinvoice.automation;

import com.geneinvoice.MovableClock;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.ChangeOrigin;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingStatus;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A rule's change above the region's limit (D11, §5.10.5): the consumer asks for it as the owner through the same
 * gate as a person, so it is held with the owner as its maker and AUTOMATION as its origin; the effect row keeps a
 * redelivered or repeated event from asking twice; the owner cannot approve it; and once someone else does, the
 * promise is made exactly once, with its co-assignees, who are told, and its trail names the owner and the checker.
 */
class AutomationHoldTest extends AutomationTestBase {

    @Autowired PocService pocService;
    @Autowired AuditLogRepository auditLogRepository;

    User admin;
    User owen;
    User colin;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        roleWith("PROMISE_RULER", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.SCOPE_OVERRIDE, Privileges.POC_VIEW, Privileges.NOTIFICATION_VIEW,
                Privileges.CHANGE_APPROVE);
        // An approver himself: still not of what his own rule asked for.
        owen = user("owen.owner", "PROMISE_RULER");
        colin = user("colin.collector", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        invoiceId = invoice(acme, widget, 2, null);
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(defaultRegion())
                .operation(ApprovalOperation.PROMISE_CREATE).amount(new BigDecimal("100.00")).windowHours(0)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
    }

    private Map<String, Object> promise() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", UUID.randomUUID().toString());
        a.put("type", "CREATE_PROMISE");
        a.put("repeat", "ONCE_PER_RECORD");
        a.put("amount", Map.of("source", "FIELD", "field", "Invoice.Balance"));
        a.put("promisedDate", Map.of("base", "TODAY", "offsetDays", 7));
        a.put("linkRecord", true);
        a.put("onlyIfNoOpenPromise", false);
        a.put("assignees", List.of(userToken(colin), userToken(admin)));
        return a;
    }

    private void touch(String notes) {
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    private ResultActions approve(Long pendingChangeId, User as) throws Exception {
        return send(post("/api/approvals/" + pendingChangeId + "/approve"), as, Map.of());
    }

    private List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(u.getId()) && n.getType().equals(type)).toList();
    }

    @Test
    void aRulesPromiseAboveTheLimitIsHeldOnceAndMadeOnceSomeoneElseApprovesIt() throws Exception {
        Long ruleId = saveRule(owen, rule("Promise", "INVOICE", "ENTITY_UPDATED", null, promise()));
        touch("first");
        Long eventId = events(AutomationEvent.UPDATED).stream().filter(e -> "INVOICE".equals(e.getEntityType()))
                .reduce((first, last) -> last).orElseThrow().getId();

        // The worker stops just after the action committed: the event is redelivered once its lease runs out.
        automationFaults.crashAfterCommit(1);
        assertThatThrownBy(this::drain).isInstanceOf(RecordingAutomationFaults.SimulatedCrash.class);
        MovableClock.SHARED.set(MovableClock.SHARED.instant().plus(Duration.ofMinutes(6)));
        drain();
        assertThat(event(eventId).getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(event(eventId).getClaims()).isEqualTo(2);
        // And a later change to the invoice asks again: done once per record, so it does not.
        touch("second");
        drain();

        List<PendingChange> held = pendingChangeRepository.findAll();
        assertThat(held).singleElement().satisfies(pc -> {
            assertThat(pc.getStatus()).isEqualTo(PendingStatus.PENDING);
            assertThat(pc.getOperation()).isEqualTo(ApprovalOperation.PROMISE_CREATE);
            assertThat(pc.getOrigin()).isEqualTo(ChangeOrigin.AUTOMATION);
            assertThat(pc.getMakerUserId()).as("the rule's owner is its maker").isEqualTo(owen.getId());
            assertThat(pc.getRuleId()).isEqualTo(ruleId);
            assertThat(pc.getAutomationEventId()).isEqualTo(eventId);
            assertThat(pc.getAmount()).isEqualByComparingTo("200.00");
        });
        PendingChange pc = held.get(0);
        assertThat(promiseRepository.findAll()).isEmpty();
        assertThat(recordAssigneeRepository.findAll()).as("nobody is assigned to a promise not yet made").isEmpty();
        List<AutomationEffect> effects = automationEffectRepository.findAll().stream()
                .sorted(Comparator.comparing(AutomationEffect::getId)).toList();
        assertThat(effects).extracting(AutomationEffect::getStatus)
                .containsExactly(EffectStatus.DONE, EffectStatus.SKIPPED);
        assertThat(effects.get(0).getResultType()).isEqualTo("PENDING_CHANGE");
        assertThat(effects.get(0).getResultId()).isEqualTo(pc.getId());
        assertThat(effects.get(0).getDedupeKey()).isNotNull();
        assertThat(effects.get(1).getMessage()).isEqualTo("Already done for this record");

        // The owner made it, so the owner cannot be the one to approve it.
        ResultActions own = approve(pc.getId(), owen);
        assertThat(own.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(body(own).get("message").asText()).isEqualTo("You cannot approve a change you requested");
        assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.PENDING);

        assertThat(approve(pc.getId(), admin).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingStatus.APPROVED);
        List<PaymentPromise> made = promiseRepository.findAll();
        assertThat(made).hasSize(1);
        PaymentPromise p = made.get(0);
        assertThat(p.getAmount()).isEqualByComparingTo("200.00");
        assertThat(p.getCollectionPoc().getId()).isEqualTo(collector.getId());
        assertThat(p.getCreatedByUserId()).isEqualTo(owen.getId());
        assertThat(recordAssigneeRepository.findAll()).filteredOn(r -> r.getOwnerType().equals(AssignedTo.PROMISE)
                        && r.getOwnerId().equals(p.getId()) && r.getRemovedAt() == null)
                .extracting(r -> r.getUserId()).containsExactly(colin.getId(), admin.getId());
        assertThat(noticesTo(colin, "PROMISE_ASSIGNED")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/promises/" + p.getId()));
        assertThat(noticesTo(admin, "PROMISE_ASSIGNED")).as("a rule's change: its checker is told too").hasSize(1);

        AuditLog created = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("PROMISE", p.getId())
                .stream().filter(a -> a.getAction().equals("PROMISE_CREATED")).findFirst().orElseThrow();
        assertThat(created.getChangedByUserId()).as("made as the rule's owner").isEqualTo(owen.getId());
        assertThat(created.getApprovedByUserId()).isEqualTo(admin.getId());
        assertThat(created.getPendingChangeId()).isEqualTo(pc.getId());
        assertThat(created.getOrigin()).isEqualTo("APPROVAL");

        // Nothing left to approve, and nothing more happens on the next change.
        touch("third");
        drain();
        assertThat(promiseRepository.findAll()).hasSize(1);
        assertThat(pendingChangeRepository.findAll()).hasSize(1);
    }
}
