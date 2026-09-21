package com.geneinvoice.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The part that makes the queue a promise rather than a hope (R8), plus the clock half of the
 * trigger side.
 *
 * <p>The sweep is the real durability guarantee. A nudge after commit is best-effort and always
 * will be: the process can die between the commit and the publish, the in-process thread's backlog
 * does not survive a restart, and a broker can drop a message. None of that loses work, because the
 * row is in the table and the sweep publishes anything queued that nobody has picked up. It also
 * closes out rows whose worker died half way — those are marked failed and NOT re-run, since the
 * action may already have happened and a second task is worse than a missing one.
 *
 * <p>The daily and weekly runs walk their rules and queue one row per matching record. They are not
 * caught up at startup, and that is deliberate: a run missed because the service was down would
 * otherwise fire at whatever hour the service happened to come back, which is nobody's idea of
 * "every morning". What startup does catch up is the outbox, which is work that was already
 * decided. A run that fires twice — two instances, a restart mid-run — costs nothing, because the
 * day or week is part of the key (R4).
 */
@Component
@Slf4j
public class AutomationScheduler {

    /** Rows published this recently are still on their way to a worker; the sweep leaves them alone. */
    static final Duration REPUBLISH_AFTER = Duration.ofMinutes(2);
    /** A claimed row nobody has touched for this long belongs to a worker that is not coming back. */
    static final Duration STALE_RUNNING = Duration.ofMinutes(10);
    static final int SWEEP_BATCH = 200;
    /**
     * The most records one scheduled run of one rule may queue. A rule with no filters matches the
     * whole table, and a daily run that queued a hundred thousand rows would be a denial of service
     * the app performed on itself. Hitting the cap is logged, because a rule that always hits it is
     * a rule whose WHERE is too wide to mean anything.
     */
    static final int RUN_LIMIT = 1000;

    private final AutomationEventRepository events;
    private final AutomationRuleRepository rules;
    private final AutomationEvents outbox;
    private final AutomationMatcher matcher;
    private final AutomationJson json;
    private final AutomationQueue queue;
    private final TransactionTemplate transactions;
    private final int retainDays;

    public AutomationScheduler(AutomationEventRepository events, AutomationRuleRepository rules,
                               AutomationEvents outbox, AutomationMatcher matcher, AutomationJson json,
                               AutomationQueue queue, TransactionTemplate transactions,
                               @Value("${app.automation.retain-days:30}") int retainDays) {
        this.events = events;
        this.rules = rules;
        this.outbox = outbox;
        this.matcher = matcher;
        this.json = json;
        this.queue = queue;
        this.transactions = transactions;
        this.retainDays = retainDays;
    }

    // ---- the sweep -----------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.automation.sweep-interval-ms:60000}",
            initialDelayString = "${app.automation.sweep-interval-ms:60000}")
    public void sweepOnTimer() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Automation sweep failed: {}", e.getMessage());
        }
    }

    /** Catches up the outbox with whatever fell due while the service was down. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweepOnTimer();
    }

    public void sweep() {
        sweep(Instant.now());
    }

    /** @param now the sweep's own clock, so a test can move time without waiting for it */
    void sweep(Instant now) {
        Instant touchedBefore = now.minus(STALE_RUNNING);
        List<Long> stale = transactions.execute(status ->
                events.findStaleRunning(touchedBefore, PageRequest.of(0, SWEEP_BATCH)));
        int interrupted = 0;
        for (Long id : stale == null ? List.<Long>of() : stale) {
            if (Boolean.TRUE.equals(transactions.execute(status -> interrupted(id, touchedBefore)))) {
                interrupted++;
            }
        }
        if (interrupted > 0) {
            log.warn("Marked {} interrupted automation run(s) as failed", interrupted);
        }

        List<Long> lost = transactions.execute(status ->
                events.findUnpublished(now, now.minus(REPUBLISH_AFTER), PageRequest.of(0, SWEEP_BATCH)));
        if (lost != null && !lost.isEmpty()) {
            log.info("Publishing {} automation event(s) again", lost.size());
            transactions.executeWithoutResult(status -> events.markEnqueued(lost, now));
            queue.enqueueAll(lost);
        }

        purge(now);
    }

    /**
     * A row claimed by a worker that never came back. It is settled with the lock the read takes,
     * and checked again under it, because the worker may have finished between the two reads. It is
     * not re-run: see the class note.
     */
    private boolean interrupted(Long eventId, Instant touchedBefore) {
        AutomationEvent event = events.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() != AutomationEventStatus.RUNNING
                || event.getUpdatedAt() == null || !event.getUpdatedAt().isBefore(touchedBefore)) {
            return false;
        }
        event.setStatus(AutomationEventStatus.FAILED);
        event.setLastError("This run was interrupted; it may or may not have happened, so run the rule again to be sure");
        event.setNextAttemptAt(null);
        events.save(event);
        return true;
    }

    /** Drops settled rows past their keep-for, so the outbox does not grow for the life of the app. */
    private void purge(Instant now) {
        if (retainDays <= 0) return;
        int removed = transactions.execute(status ->
                events.deleteSettledBefore(now.minus(Duration.ofDays(retainDays))));
        if (removed > 0) {
            log.info("Removed {} settled automation event(s) older than {} day(s)", removed, retainDays);
        }
    }

    // ---- the clock -----------------------------------------------------------------------

    @Scheduled(cron = "${app.automation.daily-cron:0 0 7 * * *}", zone = "UTC")
    public void daily() {
        runScheduled(TriggerKind.DAILY);
    }

    @Scheduled(cron = "${app.automation.weekly-cron:0 0 7 * * MON}", zone = "UTC")
    public void weekly() {
        runScheduled(TriggerKind.WEEKLY);
    }

    private void runScheduled(TriggerKind trigger) {
        try {
            runScheduled(trigger, Instant.now());
        } catch (RuntimeException e) {
            log.warn("The {} automation run failed: {}", trigger.name().toLowerCase(Locale.ROOT),
                    e.getMessage());
        }
    }

    /**
     * Queues one row per record each enabled rule of this kind matches. One rule failing — a filter
     * whose column has gone, say — must not stop the rest of the morning's rules, so each is caught
     * on its own.
     *
     * @param now the run's own clock, which is also the bucket its rows are keyed by
     * @return how many rows were queued across every rule
     */
    int runScheduled(TriggerKind trigger, Instant now) {
        List<AutomationRule> due = rules.findByEnabledTrueAndTriggerOrderByIdAsc(trigger);
        int queued = 0;
        for (AutomationRule rule : due) {
            try {
                List<Long> ids = matcher.idsMatching(rule.getEntityType(), json.filters(rule), RUN_LIMIT);
                if (ids.size() >= RUN_LIMIT) {
                    log.warn("Rule '{}' matched at least {} {}s; only the first {} are queued this run",
                            rule.getName(), RUN_LIMIT, rule.getEntityType().noun(), RUN_LIMIT);
                }
                queued += outbox.enqueueForRule(rule, ids, now).size();
            } catch (RuntimeException e) {
                log.warn("Rule '{}' could not be run: {}", rule.getName(), e.getMessage());
            }
        }
        if (queued > 0) {
            log.info("The {} automation run queued {} piece(s) of work across {} rule(s)",
                    trigger.name().toLowerCase(Locale.ROOT), queued, due.size());
        }
        return queued;
    }
}
