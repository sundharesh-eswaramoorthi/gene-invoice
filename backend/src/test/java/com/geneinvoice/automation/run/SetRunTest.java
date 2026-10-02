package com.geneinvoice.automation.run;

import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.automation.schedule.ScheduleTicker;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.product.Product;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Long set runs (§5.10.6): a run over more records than one slice of time allows checkpoints each page, hands its
 * event back and carries on from the cursor at its next claim, every page evaluated at the same moment (the slot);
 * a rule changed while its run is under way supersedes the run; a passing failure on one page after another does
 * not use up the run's attempts, as each page done gives them back; and a rule acting more often than an hour allows
 * is stopped part way and suspended, its owner told.
 */
class SetRunTest extends AutomationTestBase {

    /** Tuesday 1 January 2030, 06:00 UTC: later than any real clock the suite runs on, so time only moves on. */
    static final Instant TUESDAY = Instant.parse("2030-01-01T06:00:00Z");

    @Autowired AutomationProperties props;
    @Autowired ScheduleTicker ticker;
    @Autowired TransactionTemplate tx;

    User admin;
    User owen;
    Customer acme;
    Product widget;
    /** Five unpaid invoices, oldest first. */
    List<Long> invoices;

    int pageSize;
    long sliceBudget;
    int hourlyLimit;

    @BeforeEach
    void setUp() {
        pageSize = props.getPageSize();
        sliceBudget = props.getSliceBudgetMs();
        hourlyLimit = props.getMaxActionsPerRulePerHour();
        MovableClock.SHARED.set(TUESDAY);
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        invoices = new ArrayList<>();
        for (int i = 0; i < 5; i++) invoices.add(invoice(acme, widget, 1, null));
        drain();
    }

    @AfterEach
    void putBackTheLimits() {
        props.setPageSize(pageSize);
        props.setSliceBudgetMs(sliceBudget);
        props.setMaxActionsPerRulePerHour(hourlyLimit);
    }

    private void at(String iso) {
        MovableClock.SHARED.set(Instant.parse(iso));
    }

    private Long unpaidRule(String kind) throws Exception {
        return saveRule(owen, rule("Chase unpaid", "INVOICE", kind, and(leaf("status:eq:UNPAID")),
                everyTime(task("Chase {{Invoice.Number}}"))));
    }

    private AutomationEvent slot(Long ruleId) {
        return events(AutomationEvent.SCHEDULED).stream().filter(e -> ruleId.equals(e.getRuleId())).findFirst()
                .orElseThrow();
    }

    private void payInFull(Long invoiceId) {
        actAs(admin);
        pay(acme, invoiceRepository.findById(invoiceId).orElseThrow().getTotal().toPlainString(), invoiceId);
    }

    @Test
    void aScheduledRunHandsItsEventBackPageByPageAndCarriesOnFromTheCursorAtTheSlot() throws Exception {
        props.setPageSize(2);
        props.setSliceBudgetMs(0);
        Long ruleId = unpaidRule("SCHEDULE_DAILY");
        at("2030-01-01T09:10:00Z");
        SecurityContextHolder.clearContext();
        ticker.tick(MovableClock.SHARED.instant());

        // After the slot and before the drain: the last invoice is paid, and a new one made. As of 09:00 the first
        // was unpaid and the second did not exist.
        at("2030-01-01T09:20:00Z");
        payInFull(invoices.get(4));
        actAs(admin);
        Long madeLater = invoice(acme, widget, 1, null);
        at("2030-01-01T09:30:00Z");
        drain();

        AutomationEvent ev = slot(ruleId);
        assertThat(ev.getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(ev.getClaims()).as("pages [1,2] and [3,4] each ended a slice; [5] finished the run")
                .isEqualTo(3);
        assertThat(ev.getCursorId()).isEqualTo(invoices.get(4));
        assertThat(ev.getAttempts()).as("handing back is not a failed attempt").isZero();

        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyElementsOf(invoices)
                .doesNotContain(madeLater);
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getMatched()).as("counted once across the slices").isEqualTo(5);
        assertThat(run.getDone()).isEqualTo(5);
        assertThat(tasksOf(ruleId)).extracting(Task::getRunId).containsOnly(run.getId());
    }

    @Test
    void aRunCarriesOnFromItsCheckpointWhenItsEventIsPickedUpAgain() throws Exception {
        props.setPageSize(2);
        Long ruleId = saveRule(owen, rule("By hand", "INVOICE", "MANUAL", null,
                everyTime(task("Look at {{Invoice.Number}}"))));
        send(post(RULES + "/" + ruleId + "/run"), owen, Map.of()).andExpect(status().isAccepted());
        AutomationEvent ev = events(AutomationEvent.MANUAL).get(0);
        // A worker got through the first page and then stopped: its checkpoint says so.
        tx.executeWithoutResult(s -> {
            AutomationEvent e = automationEventRepository.findById(ev.getId()).orElseThrow();
            e.setCursorId(invoices.get(1));
            automationEventRepository.save(e);
            AutomationRun r = automationRunRepository.findByRuleIdAndEventId(ruleId, ev.getId()).orElseThrow();
            r.setStartedAt(MovableClock.SHARED.instant());
            r.setRuleVersion(automationRuleRepository.findById(ruleId).orElseThrow().getVersion());
            r.setMatched(2);
            automationRunRepository.save(r);
        });
        drain();

        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyElementsOf(invoices.subList(2, 5));
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getMatched()).isEqualTo(5);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void aRuleChangedWhileItsRunIsUnderWaySupersedesTheRun() throws Exception {
        props.setPageSize(2);
        Long ruleId = unpaidRule("SCHEDULE_DAILY");
        at("2030-01-01T09:10:00Z");
        SecurityContextHolder.clearContext();
        ticker.tick(MovableClock.SHARED.instant());
        // While the first record is acted on, someone edits the rule.
        automationFaults.onNextBeforeAction(() -> elsewhere(() -> tx.executeWithoutResult(s -> {
            AutomationRule r = automationRuleRepository.findById(ruleId).orElseThrow();
            r.setDescription("Changed while running");
            automationRuleRepository.save(r);
        })));
        drain();

        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(run.getMessage()).isEqualTo("Stopped: the rule was changed while it ran");
        assertThat(run.getMatched()).isEqualTo(2);
        assertThat(tasksOf(ruleId)).as("only the first page, under the old rule").extracting(Task::getEntityId)
                .containsExactlyElementsOf(invoices.subList(0, 2));
        assertThat(slot(ruleId).getStatus()).isEqualTo(AutomationEvent.DONE);
    }

    /** The next action fails as a lock timeout would, the one after it goes through, and so on, {@code times} times. */
    private void failEveryOtherAction(int times) {
        if (times == 0) return;
        automationFaults.onNextBeforeAction(() -> {
            automationFaults.onNextBeforeAction(() -> failEveryOtherAction(times - 1));
            throw new CannotAcquireLockException("customer is busy");
        });
    }

    @Test
    void aPassingFailureOnEveryPageDoesNotGiveUpOnARunThatMakesProgress() throws Exception {
        props.setPageSize(1);
        Long ruleId = saveRule(owen, rule("By hand", "INVOICE", "MANUAL", null,
                everyTime(task("Look at {{Invoice.Number}}"))));
        send(post(RULES + "/" + ruleId + "/run"), owen, Map.of()).andExpect(status().isAccepted());
        // Five failures in all, more than the attempts an event has, but never twice on the same page.
        failEveryOtherAction(5);
        for (int i = 0; i < 6; i++) {
            drain();
            MovableClock.SHARED.set(MovableClock.SHARED.instant().plus(Duration.ofMinutes(1)));
        }

        AutomationEvent ev = events(AutomationEvent.MANUAL).get(0);
        assertThat(ev.getStatus()).isEqualTo(AutomationEvent.DONE);
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyElementsOf(invoices);
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getMatched()).isEqualTo(5);
        assertThat(run.getDone()).isEqualTo(5);
    }

    @Test
    void aRuleActingMoreOftenThanAnHourAllowsIsStoppedAndSuspended() throws Exception {
        props.setMaxActionsPerRulePerHour(3);
        Long ruleId = saveRule(owen, rule("By hand", "INVOICE", "MANUAL", null,
                everyTime(task("Look at {{Invoice.Number}}"))));
        send(post(RULES + "/" + ruleId + "/run"), owen, Map.of()).andExpect(status().isAccepted());
        drain();

        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyElementsOf(invoices.subList(0, 3));
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.PARTIAL);
        assertThat(run.getMessage())
                .isEqualTo("Stopped: the rule reached its limit of actions an hour and was suspended");
        assertThat(run.getMatched()).isEqualTo(3);
        AutomationRule rule = automationRuleRepository.findById(ruleId).orElseThrow();
        assertThat(rule.getSuspendedReason()).isEqualTo(SuspendReason.RATE_LIMITED);
        assertThat(notificationRepository.findAll()).filteredOn(n -> n.getUserId().equals(owen.getId()))
                .extracting(Notification::getType).contains("AUTOMATION_RULE_SUSPENDED");

        // Its next change is not acted on while it is suspended.
        send(post(RULES + "/" + ruleId + "/run"), owen, Map.of()).andExpect(status().isBadRequest());
        assertThat(tasksOf(ruleId)).hasSize(3);
    }

    /** Runs {@code work} on a thread of its own: its transaction is not the one the consumer holds open. */
    private static void elsewhere(Runnable work) {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failed.get() != null) throw new IllegalStateException(failed.get());
    }
}
