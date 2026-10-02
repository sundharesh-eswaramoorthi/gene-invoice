package com.geneinvoice.automation.schedule;

import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.LobLoadProbe;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.versions.HistoryTransactionRow;
import com.geneinvoice.history.versions.HistoryTransactionRowRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scheduled rules (A1, §5.10.8): the ticker puts each due slot in the outbox once, however many ticks race for it;
 * missed slots are caught up once or one by one, up to the limit; a weekly rule is due only on its day; a slot is
 * evaluated as of the slot itself, so what happened between the slot and the drain changes nothing (PC-1); the
 * rule's version never moves; and processed events and their moments are purged after the retention, the ones given
 * up on kept.
 */
class ScheduleTickerTest extends AutomationTestBase {

    /** Tuesday 1 January 2030, 06:00 UTC: later than any real clock the suite runs on, so time only moves on. */
    static final Instant TUESDAY = Instant.parse("2030-01-01T06:00:00Z");

    @Autowired ScheduleTicker ticker;
    @Autowired AutomationProperties props;
    @Autowired HistoryTransactionRowRepository historyTransactions;
    @Autowired EntityManagerFactory emf;

    User admin;
    User owen;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        MovableClock.SHARED.set(TUESDAY);
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        invoiceId = invoice(acme, widget, 2, null);
        // The invoice's own events come before any rule; each test starts from an empty queue.
        drain();
    }

    private void at(String iso) {
        MovableClock.SHARED.set(Instant.parse(iso));
    }

    private int tick() {
        SecurityContextHolder.clearContext();
        return ticker.tick(MovableClock.SHARED.instant());
    }

    private Long dailyRule(String catchUp, Object condition) throws Exception {
        Map<String, Object> body = rule("Daily chase", "INVOICE", "SCHEDULE_DAILY", condition,
                everyTime(task("Chase {{Invoice.Number}}")));
        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = new LinkedHashMap<>((Map<String, Object>) body.get("trigger"));
        trigger.put("catchUp", catchUp);
        return saveRule(owen, with(body, "trigger", trigger));
    }

    private AutomationRule stored(Long ruleId) {
        return automationRuleRepository.findById(ruleId).orElseThrow();
    }

    private List<Instant> slotsQueued(Long ruleId) {
        return events(AutomationEvent.SCHEDULED).stream().filter(e -> ruleId.equals(e.getRuleId()))
                .map(AutomationEvent::getScheduledFor).toList();
    }

    @Test
    void aSavedScheduleKnowsItsNextSlotAndATickBeforeItQueuesNothing() throws Exception {
        Long ruleId = dailyRule("ONE", null);
        assertThat(stored(ruleId).getNextRunAt()).isEqualTo(Instant.parse("2030-01-01T09:00:00Z"));

        at("2030-01-01T08:59:00Z");
        assertThat(tick()).isZero();
        assertThat(slotsQueued(ruleId)).isEmpty();

        at("2030-01-01T09:00:30Z");
        assertThat(tick()).isEqualTo(1);
        assertThat(slotsQueued(ruleId)).containsExactly(Instant.parse("2030-01-01T09:00:00Z"));
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.QUEUED);
            assertThat(r.getTriggerType()).isEqualTo(AutomationEvent.SCHEDULED);
            assertThat(r.getScheduledFor()).isEqualTo(Instant.parse("2030-01-01T09:00:00Z"));
        });
        assertThat(stored(ruleId).getNextRunAt()).isEqualTo(Instant.parse("2030-01-02T09:00:00Z"));
        assertThat(tick()).as("the slot is queued once").isZero();

        drain();
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(runsOf(ruleId)).singleElement().extracting(AutomationRun::getStatus)
                .isEqualTo(RunStatus.SUCCEEDED);
    }

    /**
     * The scheduler thread ticks with no transaction open, and a rule keeps its condition and actions as large
     * objects: on PostgreSQL reading them there failed every tick ("Unable to access lob stream", E2E AUT-R1-2), so
     * no schedule ever fired. H2 reads them anyway, so the probe checks that every such read had a transaction.
     */
    @Test
    void aTickWithNoTransactionOpenReadsTheDueRulesInOneAndQueuesTheSlot() throws Exception {
        Long ruleId = dailyRule("ONE", null);
        at("2030-01-01T09:00:30Z");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("the test ticks as the scheduler does, outside any transaction").isFalse();

        int[] queued = new int[1];
        List<String> outside = LobLoadProbe.during(emf, () -> queued[0] = tick());

        assertThat(outside).as("rules (with @Lob fields) loaded outside a transaction").isEmpty();
        assertThat(queued[0]).isEqualTo(1);
        assertThat(slotsQueued(ruleId)).containsExactly(Instant.parse("2030-01-01T09:00:00Z"));
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getTriggerType()).isEqualTo(AutomationEvent.SCHEDULED);
            assertThat(r.getScheduledFor()).isEqualTo(Instant.parse("2030-01-01T09:00:00Z"));
        });
        assertThat(stored(ruleId).getNextRunAt()).isEqualTo(Instant.parse("2030-01-02T09:00:00Z"));

        List<String> drained = LobLoadProbe.during(emf, this::drain);
        assertThat(drained).as("the worker's reads of the slot's event and rule").isEmpty();
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(runsOf(ruleId)).singleElement().extracting(AutomationRun::getStatus)
                .isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void threeMissedDaysAreCaughtUpOnceByDefault() throws Exception {
        Long ruleId = dailyRule("ONE", null);

        // Nothing ticked on the 1st, 2nd or 3rd (the application was down).
        at("2030-01-03T10:00:00Z");
        assertThat(tick()).isEqualTo(1);

        assertThat(slotsQueued(ruleId)).as("one run, at the latest slot")
                .containsExactly(Instant.parse("2030-01-03T09:00:00Z"));
        assertThat(runsOf(ruleId)).singleElement().extracting(AutomationRun::getMessage)
                .isEqualTo("Caught up once for 3 missed slots");
        AutomationRule rule = stored(ruleId);
        assertThat(rule.getNextRunAt()).isEqualTo(Instant.parse("2030-01-04T09:00:00Z"));
        assertThat(rule.getLastScheduledFor()).isEqualTo(Instant.parse("2030-01-03T09:00:00Z"));

        drain();
        assertThat(tasksOf(ruleId)).hasSize(1);
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(r.getMessage()).as("kept when the run finished").isEqualTo("Caught up once for 3 missed slots");
        });
    }

    @Test
    void threeMissedDaysAreRunOneByOneWhenTheRuleSaysAllUpToTheLimit() throws Exception {
        Long ruleId = dailyRule("ALL", null);
        at("2030-01-03T10:00:00Z");
        assertThat(tick()).isEqualTo(3);

        assertThat(slotsQueued(ruleId)).containsExactly(Instant.parse("2030-01-01T09:00:00Z"),
                Instant.parse("2030-01-02T09:00:00Z"), Instant.parse("2030-01-03T09:00:00Z"));
        assertThat(runsOf(ruleId)).hasSize(3).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(RunStatus.QUEUED));
        drain();
        assertThat(tasksOf(ruleId)).as("an every-time task for each slot").hasSize(3);

        // Past the limit only the latest slots run; the first run names the ones left out.
        int limit = props.getMaxCatchUpSlots();
        props.setMaxCatchUpSlots(2);
        try {
            at("2030-01-07T10:00:00Z");
            assertThat(tick()).isEqualTo(2);
        } finally {
            props.setMaxCatchUpSlots(limit);
        }
        assertThat(slotsQueued(ruleId)).endsWith(Instant.parse("2030-01-06T09:00:00Z"),
                Instant.parse("2030-01-07T09:00:00Z"));
        List<AutomationRun> runs = runsOf(ruleId);
        assertThat(runs).hasSize(5);
        assertThat(runs.get(3).getMessage()).isEqualTo(
                "2 earlier slots were not run (2030-01-04 to 2030-01-05): at most 2 missed slots are caught up");
        assertThat(runs.get(4).getMessage()).isNull();
    }

    @Test
    void twoTicksAtOnceQueueTheSlotOnce() throws Exception {
        Long ruleId = dailyRule("ONE", null);
        at("2030-01-01T09:05:00Z");
        Instant now = MovableClock.SHARED.instant();
        SecurityContextHolder.clearContext();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> ticks = List.of(
                    pool.submit(() -> {
                        go.await();
                        return ticker.tick(now);
                    }),
                    pool.submit(() -> {
                        go.await();
                        return ticker.tick(now);
                    }));
            go.countDown();
            int queued = 0;
            for (Future<Integer> t : ticks) queued += t.get(30, TimeUnit.SECONDS);
            assertThat(queued).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(slotsQueued(ruleId)).containsExactly(Instant.parse("2030-01-01T09:00:00Z"));
        assertThat(runsOf(ruleId)).hasSize(1);
    }

    @Test
    void aDisabledRuleIsNotTicked() throws Exception {
        Long off = saveRule(owen, with(rule("Off", "INVOICE", "SCHEDULE_DAILY", null,
                everyTime(task("Chase"))), "enabled", false));
        Long switchedOff = dailyRule("ONE", null);
        send(post(RULES + "/" + switchedOff + "/disable"), owen, Map.of()).andExpect(status().isOk());

        assertThat(stored(off).getNextRunAt()).isNull();
        assertThat(stored(switchedOff).getNextRunAt()).isNull();
        at("2030-01-02T10:00:00Z");
        assertThat(tick()).isZero();
        assertThat(events(AutomationEvent.SCHEDULED)).isEmpty();

        // Switched on again, it is due from then on: the days it was off are not caught up.
        send(post(RULES + "/" + switchedOff + "/enable"), owen, Map.of()).andExpect(status().isOk());
        assertThat(stored(switchedOff).getNextRunAt()).isEqualTo(Instant.parse("2030-01-03T09:00:00Z"));
    }

    @Test
    void tickingNeverMovesTheRulesVersion() throws Exception {
        Long ruleId = dailyRule("ALL", null);
        Long version = stored(ruleId).getVersion();

        at("2030-01-03T10:00:00Z");
        tick();
        at("2030-01-04T10:00:00Z");
        tick();

        AutomationRule rule = stored(ruleId);
        assertThat(rule.getVersion()).as("an editor's version stays good").isEqualTo(version);
        assertThat(rule.getNextRunAt()).isEqualTo(Instant.parse("2030-01-05T09:00:00Z"));
        assertThat(slotsQueued(ruleId)).hasSize(4);
    }

    @Test
    void aWeeklyRuleIsDueOnlyOnItsDayAtItsTimeInItsZone() throws Exception {
        at("2030-01-05T12:00:00Z");
        Map<String, Object> body = rule("Monday chase", "INVOICE", "SCHEDULE_WEEKLY", null,
                everyTime(task("Weekly {{Invoice.Number}}")));
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("kind", "SCHEDULE_WEEKLY");
        trigger.put("entityType", "INVOICE");
        trigger.put("time", "09:00");
        trigger.put("day", "MONDAY");
        trigger.put("zone", "+05:30");
        Long ruleId = saveRule(owen, with(body, "trigger", trigger));
        // 09:00 on Monday 7 January in India is 03:30 UTC.
        assertThat(stored(ruleId).getNextRunAt()).isEqualTo(Instant.parse("2030-01-07T03:30:00Z"));

        at("2030-01-06T12:00:00Z");
        assertThat(tick()).as("Sunday").isZero();
        at("2030-01-07T03:00:00Z");
        assertThat(tick()).as("Monday, before nine in India").isZero();
        assertThat(slotsQueued(ruleId)).isEmpty();

        at("2030-01-07T04:00:00Z");
        assertThat(tick()).isEqualTo(1);
        assertThat(slotsQueued(ruleId)).containsExactly(Instant.parse("2030-01-07T03:30:00Z"));
        assertThat(stored(ruleId).getNextRunAt()).isEqualTo(Instant.parse("2030-01-14T03:30:00Z"));

        drain();
        String number = invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();
        assertThat(tasksOf(ruleId)).extracting(Task::getTitle).containsExactly("Weekly " + number);
    }

    @Test
    void aSlotIsEvaluatedAsOfTheSlotSoAChangeBeforeTheDrainDoesNotUndoIt() throws Exception {
        actAs(admin);
        Long other = invoice(acme, widget, 1, null);
        Long ruleId = dailyRule("ONE", and(leaf("status:eq:UNPAID")));

        at("2030-01-01T09:10:00Z");
        tick();
        // Paid after the slot, before the consumer got to it: at 09:00 it was still unpaid.
        at("2030-01-01T09:20:00Z");
        actAs(admin);
        pay(acme, "200.00", invoiceId);
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.FULLY_PAID);

        at("2030-01-01T09:30:00Z");
        drain();

        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyInAnyOrder(invoiceId, other);
        assertThat(runsOf(ruleId)).singleElement().satisfies(r -> {
            assertThat(r.getMatched()).isEqualTo(2);
            assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        });

        // The next slot sees it paid.
        at("2030-01-02T09:10:00Z");
        tick();
        drain();
        assertThat(tasksOf(ruleId)).extracting(Task::getEntityId).containsExactlyInAnyOrder(invoiceId, other, other);
    }

    @Test
    void thePurgeTakesOldProcessedEventsAndMomentsAndKeepsThoseGivenUpOn() {
        Instant now = Instant.parse("2030-02-01T00:00:00Z");
        Instant old = now.minus(Duration.ofDays(props.getEventRetentionDays() + 1));
        Instant recent = now.minus(Duration.ofDays(1));
        Long oldDone = event(AutomationEvent.DONE, old);
        Long oldDead = event(AutomationEvent.DEAD, old);
        Long recentDone = event(AutomationEvent.DONE, recent);
        Long oldMoment = historyTransactions.save(new HistoryTransactionRow(UUID.randomUUID().toString(), old))
                .getId();
        Long recentMoment = historyTransactions.save(new HistoryTransactionRow(UUID.randomUUID().toString(), recent))
                .getId();

        at("2030-02-01T00:00:00Z");
        tick();

        assertThat(automationEventRepository.existsById(oldDone)).isFalse();
        assertThat(automationEventRepository.existsById(oldDead)).as("kept for an administrator").isTrue();
        assertThat(automationEventRepository.existsById(recentDone)).isTrue();
        assertThat(historyTransactions.existsById(oldMoment)).isFalse();
        assertThat(historyTransactions.existsById(recentMoment)).isTrue();

        // Not at every tick: the next purge is an hour on.
        Long laterOld = event(AutomationEvent.DONE, old);
        at("2030-02-01T00:30:00Z");
        tick();
        assertThat(automationEventRepository.existsById(laterOld)).isTrue();
        at("2030-02-01T01:00:00Z");
        tick();
        assertThat(automationEventRepository.existsById(laterOld)).isFalse();
    }

    private Long event(String status, Instant processedAt) {
        return automationEventRepository.save(AutomationEvent.builder()
                .kind(AutomationEvent.UPDATED)
                .entityType("INVOICE")
                .entityId(invoiceId)
                .depth(0)
                .occurredAt(processedAt.minusSeconds(5))
                .processedAt(processedAt)
                .updatedAt(processedAt)
                .status(status)
                .build()).getId();
    }
}
