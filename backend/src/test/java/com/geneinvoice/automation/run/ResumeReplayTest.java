package com.geneinvoice.automation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resuming a suspended rule (§5.10.7, PC-18): the answer says how many changes were made while it was suspended;
 * without replay none of them is acted on; with replay each is gone through at the moment it happened (so a change
 * that matched then acts, though the record has changed since), and a second replay of the same changes repeats
 * nothing. A change still waiting for the consumer when the rule was suspended is among them, and a replay that the
 * rule's suspension stops part way leaves the changes it did not reach to the next resume.
 */
class ResumeReplayTest extends AutomationTestBase {

    /** Tuesday 1 January 2030, 06:00 UTC: later than any real clock the suite runs on, so time only moves on. */
    static final Instant TUESDAY = Instant.parse("2030-01-01T06:00:00Z");

    @Autowired RuleService ruleService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AutomationProperties props;

    User admin;
    User owen;
    Customer acme;
    Product widget;
    Long invoiceId;
    Long ruleId;
    int hourlyLimit;

    @BeforeEach
    void setUp() throws Exception {
        hourlyLimit = props.getMaxActionsPerRulePerHour();
        MovableClock.SHARED.set(TUESDAY);
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        invoiceId = invoice(acme, widget, 2, null);
        drain();
        ruleId = saveRule(owen, rule("Chase unpaid", "INVOICE", "ENTITY_UPDATED", and(leaf("status:eq:UNPAID")),
                everyTime(task("Chase {{Invoice.Number}}"))));

        // Suspended at 07:00; while it is, the invoice is noted (still unpaid) and then paid in full.
        at("2030-01-01T07:00:00Z");
        ruleService.suspend(ruleId, SuspendReason.OWNER_LACKS_RIGHTS, "Briefly without the rights it needs");
        at("2030-01-01T07:10:00Z");
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest("noted", null, null, null, null));
        at("2030-01-01T07:20:00Z");
        actAs(admin);
        pay(acme, invoiceRepository.findById(invoiceId).orElseThrow().getTotal().toPlainString(), invoiceId);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.FULLY_PAID);
        drain();
        assertThat(runsOf(ruleId)).as("nothing runs while it is suspended").isEmpty();
        at("2030-01-01T08:00:00Z");
    }

    @AfterEach
    void putBackTheLimit() {
        props.setMaxActionsPerRulePerHour(hourlyLimit);
    }

    private void at(String iso) {
        MovableClock.SHARED.set(Instant.parse(iso));
    }

    private JsonNode resume(boolean replay) throws Exception {
        return body(send(post(RULES + "/" + ruleId + "/resume"), owen, Map.of("replay", replay))
                .andExpect(status().isOk()));
    }

    private AutomationRun runOf(AutomationEvent ev) {
        return runsOf(ruleId).stream().filter(r -> r.getEventId().equals(ev.getId())).findFirst().orElseThrow();
    }

    private List<AuditLog> resumedAudit() {
        return auditLogRepository.findAll().stream()
                .filter(a -> "RULE".equals(a.getEntityType()) && ruleId.equals(a.getEntityId())
                        && "RULE_RESUMED".equals(a.getAction()))
                .toList();
    }

    @Test
    void resumingWithoutReplayCountsTheChangesMadeMeanwhileAndActsOnNone() throws Exception {
        JsonNode resumed = resume(false);

        assertThat(resumed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(resumed.get("skippedWhileSuspended").asLong())
                .as("the note and the payment each updated the invoice").isEqualTo(2);
        assertThat(events(AutomationEvent.REPLAY)).isEmpty();
        assertThat(resumedAudit()).singleElement().satisfies(a -> assertThat(a.getAfterJson())
                .contains("\"replay\":false"));
        drain();
        assertThat(tasksOf(ruleId)).isEmpty();
        assertThat(runsOf(ruleId)).isEmpty();
    }

    @Test
    void resumingWithReplayGoesThroughEachChangeAtItsOwnMomentOnce() throws Exception {
        JsonNode resumed = resume(true);
        assertThat(resumed.get("skippedWhileSuspended").asLong()).isEqualTo(2);
        AutomationEvent replay = events(AutomationEvent.REPLAY).get(0);
        assertThat(replay.getRuleId()).isEqualTo(ruleId);
        assertThat(replay.getScheduledFor()).isEqualTo(Instant.parse("2030-01-01T07:00:00Z"));
        assertThat(replay.getOccurredAt()).isEqualTo(Instant.parse("2030-01-01T08:00:00Z"));
        assertThat(resumedAudit()).singleElement().satisfies(a -> assertThat(a.getAfterJson())
                .contains("\"replay\":true"));

        drain();

        // When noted the invoice was unpaid, so that change acts; when paid it no longer matched. Asked now, neither
        // would.
        List<Task> tasks = tasksOf(ruleId);
        assertThat(tasks).singleElement().extracting(Task::getEntityId).isEqualTo(invoiceId);
        List<AutomationRun> runs = runsOf(ruleId);
        AutomationRun umbrella = runs.stream().filter(r -> r.getEventId().equals(replay.getId())).findFirst()
                .orElseThrow();
        assertThat(umbrella.getTriggerType()).isEqualTo(AutomationEvent.REPLAY);
        assertThat(umbrella.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(umbrella.getRequestedByUserId()).isEqualTo(owen.getId());
        assertThat(umbrella.getMessage()).isEqualTo("Replayed 2 changes made while the rule was suspended");
        AutomationRun acted = runs.stream().filter(r -> !r.getEventId().equals(replay.getId())).findFirst()
                .orElseThrow();
        assertThat(acted.getTriggerType()).isEqualTo(AutomationEvent.REPLAY);
        assertThat(acted.getEntityId()).isEqualTo(invoiceId);
        assertThat(acted.getRequestedByUserId()).isEqualTo(owen.getId());
        assertThat(acted.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(tasks.get(0).getRunId()).isEqualTo(acted.getId());
        AutomationEvent noted = event(acted.getEventId());
        assertThat(noted.getKind()).isEqualTo(AutomationEvent.UPDATED);
        assertThat(noted.getOccurredAt()).isEqualTo(Instant.parse("2030-01-01T07:10:00Z"));

        // The same window replayed again repeats nothing: each change's run is already there.
        at("2030-01-01T08:30:00Z");
        automationEventRepository.save(AutomationEvent.builder()
                .kind(AutomationEvent.REPLAY)
                .ruleId(ruleId)
                .ruleVersion(automationRuleRepository.findById(ruleId).orElseThrow().getVersion())
                .origin("USER")
                .depth(0)
                .actorUserId(owen.getId())
                .scheduledFor(replay.getScheduledFor())
                .occurredAt(replay.getOccurredAt())
                .status(AutomationEvent.PENDING)
                .build());
        drain();
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(runsOf(ruleId)).hasSize(3);

        // From here on, changes are acted on live as usual.
        actAs(admin);
        Long another = invoice(acme, widget, 1, null);
        invoiceService.update(another, new InvoiceDtos.UpdateInvoiceRequest("fresh", null, null, null, null));
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactly(invoiceId, another);
    }

    @Test
    void aChangeStillWaitingWhenTheRuleWasSuspendedIsReplayedToo() throws Exception {
        resume(false);
        at("2030-01-01T08:05:00Z");
        actAs(admin);
        Long another = invoice(acme, widget, 1, null);
        drain();

        // The consumer is behind when the rule is suspended: the change it was not yet asked about is processed
        // while it is suspended, and so without it.
        at("2030-01-01T08:10:00Z");
        actAs(admin);
        invoiceService.update(another, new InvoiceDtos.UpdateInvoiceRequest("noted", null, null, null, null));
        at("2030-01-01T08:20:00Z");
        ruleService.suspend(ruleId, SuspendReason.OWNER_LACKS_RIGHTS, "Again without the rights it needs");
        drain();
        assertThat(tasksOf(ruleId)).isEmpty();

        at("2030-01-01T08:30:00Z");
        JsonNode resumed = resume(true);
        assertThat(resumed.get("skippedWhileSuspended").asLong()).isEqualTo(1);
        AutomationEvent replay = events(AutomationEvent.REPLAY).get(0);
        assertThat(replay.getScheduledFor()).isEqualTo(Instant.parse("2030-01-01T08:10:00Z"));
        drain();
        assertThat(tasksOf(ruleId)).singleElement().extracting(Task::getEntityId).isEqualTo(another);
        assertThat(runOf(replay).getMessage()).isEqualTo("Replayed 1 change made while the rule was suspended");
    }

    @Test
    void aReplayTheHourlyLimitStopsLeavesTheChangesNotReachedToTheNextResume() throws Exception {
        props.setMaxActionsPerRulePerHour(1);
        resume(true);
        AutomationEvent first = events(AutomationEvent.REPLAY).get(0);
        drain();

        // The note acts and uses up the hour, so the payment is not gone through, and the replay says so.
        assertThat(tasksOf(ruleId)).singleElement().extracting(Task::getEntityId).isEqualTo(invoiceId);
        AutomationRun stopped = runOf(first);
        assertThat(stopped.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(stopped.getMatched()).isEqualTo(1);
        assertThat(stopped.getMessage()).isEqualTo("Stopped: the rule reached its limit of actions an hour and was "
                + "suspended; resuming it with replay goes through the changes not reached");
        AutomationRule rule = automationRuleRepository.findById(ruleId).orElseThrow();
        assertThat(rule.getSuspendedReason()).isEqualTo(SuspendReason.RATE_LIMITED);
        assertThat(rule.getSuspendedAt()).as("the payment's moment").isEqualTo(Instant.parse("2030-01-01T07:20:00Z"));

        // Resumed again with replay, the payment is gone through (when paid it no longer matched) and the note is not
        // acted on twice.
        props.setMaxActionsPerRulePerHour(hourlyLimit);
        at("2030-01-01T09:30:00Z");
        assertThat(resume(true).get("skippedWhileSuspended").asLong()).isEqualTo(1);
        AutomationEvent second = events(AutomationEvent.REPLAY).get(1);
        assertThat(second.getScheduledFor()).isEqualTo(Instant.parse("2030-01-01T07:20:00Z"));
        drain();
        AutomationRun finished = runOf(second);
        assertThat(finished.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(finished.getMessage()).isEqualTo("Replayed 1 change made while the rule was suspended");
        assertThat(tasksOf(ruleId)).hasSize(1);
    }
}
