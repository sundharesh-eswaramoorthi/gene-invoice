package com.geneinvoice.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The reaper, on the same scheduler as everything else and throttled to once a day (A5).
 *
 * <p>WHAT IS KEPT IS THE POINT. Steps and runs are the audit trail — "what has automation done to
 * this account" is answered from {@code automation_steps} and nowhere else — so only steps that
 * finished long ago and correctly are removed. A POISONED step is NEVER removed: the step row IS
 * the dead letter, it is what the run history shows with a Retry button beside it, and deleting
 * it would turn a bug that somebody still has to look at into a silence.
 *
 * <p>Events are not the audit trail, which is why they can go at all — but an event that was
 * settled DONE with an error on it is a fan-out that GAVE UP, and that is a dead letter by the
 * same argument as a poisoned step. It is kept (A5, see deviations).
 *
 * <p>{@code deleteAllByIdInBatch} over a paged id query, the {@code WebhookDispatcher} shape, and
 * never one unpaged bulk delete: a year of events removed in one statement is exactly the
 * table-locking write this is trying to avoid.
 */
@Component
@Slf4j
public class AutomationRetention {

    /** One chunk, the shape the repositories' paged delete helpers were written for. */
    static final int CHUNK = 500;

    /** A bounded sweep: at most this many chunks per table per day, so it never runs for hours. */
    static final int MAX_CHUNKS = 20;

    private static final Duration ONCE_A_DAY = Duration.ofDays(1);

    private final AutomationEventRepository events;
    private final AutomationStepRepository steps;
    private final AutomationProperties properties;
    private final TransactionTemplate transactions;

    /**
     * The stored last-run instant the throttle reads. Per JVM and deliberately not a row: two
     * instances each reaping once a day is two harmless passes over rows that are already gone,
     * and a shared row would be a second thing to lock for no gain (A5).
     */
    private final AtomicReference<Instant> lastRun = new AtomicReference<>();

    public AutomationRetention(AutomationEventRepository events, AutomationStepRepository steps,
                               AutomationProperties properties, TransactionTemplate transactions) {
        this.events = events;
        this.steps = steps;
        this.properties = properties;
        this.transactions = transactions;
    }

    /** The scheduler's entry point. The real work is {@link #reap(Instant)}, which tests call. */
    public void sweep() {
        sweep(Instant.now());
    }

    /** The throttle, and the one time seam: once a day, whatever the sweep interval is (A5). */
    void sweep(Instant now) {
        Instant last = lastRun.get();
        if (last != null && last.plus(ONCE_A_DAY).isAfter(now)) return;
        // compareAndSet and not set: two sweeper threads arriving together must produce one pass.
        if (!lastRun.compareAndSet(last, now)) return;
        reap(now);
    }

    /** Everything the throttle guards, so a test can drive it directly and twice (A5). */
    int reap(Instant now) {
        int gone = reapEvents(now) + reapSteps(now);
        if (gone > 0) log.info("Automation retention removed {} row(s)", gone);
        return gone;
    }

    private int reapEvents(Instant now) {
        Instant before = now.minus(properties.keepEvents());
        int gone = 0;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            List<Long> page = transactions.execute(s ->
                    events.findDelivered(EventStatus.DONE, before, PageRequest.of(0, CHUNK)));
            if (page == null || page.isEmpty()) break;
            List<Long> delivered = transactions.execute(s -> events.findAllById(page).stream()
                    .filter(e -> e.getLastError() == null)
                    .map(AutomationEvent::getId).toList());
            // Only dead letters left at the head of the queue: stop rather than read the same
            // page again for ever. Tomorrow's pass sees the same rows and the same answer (A5).
            if (delivered == null || delivered.isEmpty()) break;
            transactions.executeWithoutResult(s -> events.deleteAllByIdInBatch(delivered));
            gone += delivered.size();
            if (page.size() < CHUNK) break;
        }
        return gone;
    }

    private int reapSteps(Instant now) {
        Instant before = now.minus(properties.keepSteps());
        int gone = 0;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            // DONE and SKIPPED only — the repository's own query says so, and POISONED is the
            // reason it is written as a query rather than as a status argument (A5).
            Integer removed = transactions.execute(s ->
                    steps.deleteSettledBefore(before, PageRequest.of(0, CHUNK)));
            if (removed == null || removed == 0) break;
            gone += removed;
            if (removed < CHUNK) break;
        }
        return gone;
    }
}
