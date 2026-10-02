package com.geneinvoice.automation.schedule;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.AutomationRunRepository;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.history.versions.HistoryTransactionRowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Puts scheduled rules' due slots in the outbox (§5.10.8). Each due rule, in a transaction of its own, is moved on to
 * its next slot by a conditional update, and only the tick that moved it writes the slot's SCHEDULED event and its
 * QUEUED run: two ticks at once make one event. The consumer evaluates each slot as of the slot itself (D10), so a
 * late or missed tick changes when a slot runs, never what it matches. Missed slots are caught up once (the latest)
 * or one by one (at most {@code max-catch-up-slots}). Once an hour a tick also clears out processed events, and the
 * moments they were evaluated at, once they are older than {@code event-retention-days}.
 */
@Component
@Slf4j
public class ScheduleTicker {

    static final int DUE_PAGE = 100;
    static final int PURGE_CHUNK = 1000;
    /** Enough chunks for any day's traffic; whatever is left goes at the next tick. */
    static final int MAX_PURGE_CHUNKS = 100;
    /** Both tables grow with every save, and what is old enough moves on by the day: an hour is often enough. */
    static final Duration PURGE_EVERY = Duration.ofHours(1);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationEventRepository eventRepository;
    private final AutomationRunRepository runRepository;
    private final HistoryTransactionRowRepository historyTransactions;
    private final AutomationProperties props;
    private final TransactionTemplate tx;
    private volatile Instant lastPurge;

    public ScheduleTicker(AutomationRuleRepository ruleRepository, AutomationEventRepository eventRepository,
                          AutomationRunRepository runRepository, HistoryTransactionRowRepository historyTransactions,
                          AutomationProperties props, PlatformTransactionManager transactionManager) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.historyTransactions = historyTransactions;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The slots due at {@code now}, queued; then the purge, when due. Returns how many slot events were written. */
    public int tick(Instant now) {
        int queued = 0;
        while (true) {
            List<AutomationRule> due = ruleRepository.findDueSchedules(now, PageRequest.of(0, DUE_PAGE));
            int moved = 0;
            for (AutomationRule rule : due) {
                try {
                    Integer n = tx.execute(s -> schedule(rule, now));
                    if (n != null && n >= 0) {
                        moved++;
                        queued += n;
                    }
                } catch (RuntimeException e) {
                    log.warn("automation.tick.failed rule={}: {}", rule.getId(), e.toString());
                }
            }
            if (due.size() < DUE_PAGE || moved == 0) break;
        }
        Instant last = lastPurge;
        // A clock put back (a test's) purges again at once.
        if (last == null || !now.isBefore(last.plus(PURGE_EVERY)) || now.isBefore(last)) {
            lastPurge = now;
            purge(now);
        }
        return queued;
    }

    /**
     * One due rule moved on to its next slot, and its slots queued; -1 when another tick moved it first. A slot is
     * the moment its run is evaluated at, so an outage never changes what a slot matched.
     */
    private int schedule(AutomationRule rule, Instant now) {
        ScheduleCalculator.Spec spec = ScheduleCalculator.Spec.of(rule);
        Instant expected = rule.getNextRunAt();
        Instant next = ScheduleCalculator.nextAfter(spec, now);
        List<Instant> slots;
        String note = null;
        if (AutomationRule.CATCH_UP_ALL.equals(rule.getCatchUp())) {
            Instant from = expected.minusNanos(1);
            if (rule.getLastScheduledFor() != null && rule.getLastScheduledFor().isAfter(from)) {
                from = rule.getLastScheduledFor();
            }
            ScheduleCalculator.Missed missed = ScheduleCalculator.missedSlots(spec, from, now,
                    props.getMaxCatchUpSlots());
            slots = missed.slots();
            if (missed.dropped() > 0) {
                note = missed.dropped() + (missed.dropped() == 1 ? " earlier slot was" : " earlier slots were")
                        + " not run (" + day(missed.firstDropped())
                        + (missed.dropped() == 1 ? "" : " to " + day(missed.lastDropped())) + "): at most "
                        + props.getMaxCatchUpSlots() + " missed slots are caught up";
            }
        } else {
            Instant latest = ScheduleCalculator.latestAtOrBefore(spec, now);
            slots = latest.isBefore(expected) ? List.of() : List.of(latest);
            int missed = latest.isBefore(expected) ? 0
                    : ScheduleCalculator.missedSlots(spec, expected.minusNanos(1), now, 1).dropped() + 1;
            if (missed > 1) note = "Caught up once for " + missed + " missed slots";
        }
        Instant lastSlot = slots.isEmpty() ? rule.getLastScheduledFor() : slots.get(slots.size() - 1);
        if (ruleRepository.advanceSchedule(rule.getId(), expected, next, lastSlot) == 0) return -1;
        for (int i = 0; i < slots.size(); i++) {
            AutomationEvent ev = eventRepository.save(AutomationEvent.builder()
                    .kind(AutomationEvent.SCHEDULED)
                    .ruleId(rule.getId())
                    .ruleVersion(rule.getVersion())
                    .origin(WriteOrigin.Kind.SYSTEM.name())
                    .depth(0)
                    .scheduledFor(slots.get(i))
                    .occurredAt(now)
                    .status(AutomationEvent.PENDING)
                    .build());
            AutomationRun run = AutomationRun.of(rule.getId(), rule.getVersion(), ev, RunStatus.QUEUED, null, now);
            if (i == 0) run.setMessage(note);
            runRepository.save(run);
        }
        return slots.size();
    }

    private static String day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    /**
     * Processed events older than the retention, and the moments of the changes they described, in chunks. Events
     * given up on are kept for an administrator to requeue; one whose moment was purged is then evaluated on current
     * data (§5.10.6).
     */
    void purge(Instant now) {
        Instant before = now.minus(Duration.ofDays(props.getEventRetentionDays()));
        int events = purge(() -> eventRepository.findPurgeableIds(before, PageRequest.of(0, PURGE_CHUNK)),
                eventRepository::deleteByIds);
        int moments = purge(() -> historyTransactions.findIdsBefore(before, PageRequest.of(0, PURGE_CHUNK)),
                historyTransactions::deleteByIds);
        if (events + moments > 0) {
            log.info("automation.purge events={} historyTransactions={} before={}", events, moments, before);
        }
    }

    private int purge(Supplier<List<Long>> find, Function<List<Long>, Integer> delete) {
        int total = 0;
        for (int chunk = 0; chunk < MAX_PURGE_CHUNKS; chunk++) {
            Integer n = tx.execute(s -> {
                List<Long> ids = find.get();
                return ids.isEmpty() ? 0 : delete.apply(ids);
            });
            if (n == null || n == 0) break;
            total += n;
        }
        return total;
    }
}
