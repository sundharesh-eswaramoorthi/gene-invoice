package com.geneinvoice.automation.run;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.common.DatabaseErrors;
import com.geneinvoice.common.Fit;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The outbox consumer (A5, P2; §5.10.6). Each step is a short transaction of its own: events whose worker stopped
 * are put back, due events are claimed one at a time with a lease and a fencing token, processed, and finished with
 * that token, so a worker that lost its lease changes nothing. Nothing is merged: every event keeps its own
 * provenance and is evaluated at its own moment. A transient failure is retried later with a growing wait, and given
 * up on (DEAD) after {@code max-attempts}; a database that went away stops the drain without counting an attempt. A
 * long set run keeps its lease alive page by page (the runner's heartbeat) and hands its event back when its slice of
 * time is used up, to be claimed again and carried on from its cursor.
 */
@Component
@Slf4j
public class AutomationWorker {

    /** The waits before the second, third, fourth and later attempts. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofHours(1));
    static final Duration RELEASE_WAIT = Duration.ofSeconds(30);

    private final AutomationEventRepository events;
    private final EventProcessor processor;
    private final RuleRunner runner;
    private final AutomationProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String node;
    private volatile boolean stopping;

    public AutomationWorker(AutomationEventRepository events, EventProcessor processor, RuleRunner runner,
                            AutomationProperties props, Clock clock, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.processor = processor;
        this.runner = runner;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.node = nodeId(props.getNodeId());
    }

    /** Stops between events and pages once the application is shutting down. */
    @PreDestroy
    void stop() {
        stopping = true;
    }

    public String node() {
        return node;
    }

    /**
     * Processes what is due at {@code now}, including what processing makes due (a customer's fan-out), batch after
     * batch for at most half a lease, then leaves the rest to the next poll. Each batch first puts back events whose
     * worker stopped, and every claim takes its lease from the clock at that moment, so a lease is never already
     * running out when it is granted. Never inside a transaction: each event commits on its own. Returns how many
     * events were claimed.
     */
    public int drain(Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The automation consumer must not run inside a transaction");
        }
        long budgetEnds = System.nanoTime() + Duration.ofSeconds(props.getLeaseSeconds()).toNanos() / 2;
        int claimed = 0;
        Instant at = now;
        while (!stopping) {
            for (Long id : events.findExpiredLeaseIds(at, PageRequest.of(0, props.getBatchSize()))) {
                Instant recoverAt = at;
                tx.execute(s -> events.recover(id, recoverAt));
            }
            List<Long> due = events.findDueIds(at, PageRequest.of(0, props.getBatchSize()));
            int claimedNow = 0;
            for (Long id : due) {
                if (stopping) break;
                Instant claimAt = latest(at, clock.instant());
                Instant leaseUntil = claimAt.plusSeconds(props.getLeaseSeconds());
                Integer rows = tx.execute(s -> events.claim(id, node, claimAt, leaseUntil));
                if (rows == null || rows == 0) continue;
                claimedNow++;
                int token = events.claimsOf(id);
                if (!handle(id, token)) return claimed + claimedNow;
            }
            claimed += claimedNow;
            if (due.isEmpty() || claimedNow == 0 || System.nanoTime() - budgetEnds > 0) break;
            at = latest(at, clock.instant());
        }
        return claimed;
    }

    private static Instant latest(Instant a, Instant b) {
        return b.isAfter(a) ? b : a;
    }

    /** One claimed event, processed and finished; false when the database went away and the drain must stop. */
    private boolean handle(Long id, int token) {
        if (events.claimsSinceRequeue(id) > props.getMaxClaims()) {
            dead(id, token, "Processing never finished after " + props.getMaxClaims() + " claims");
            return true;
        }
        EventProcessor.Outcome outcome;
        try {
            outcome = processor.process(id, token);
        } catch (RuntimeException e) {
            if (DatabaseErrors.isConcurrency(e)) {
                outcome = EventProcessor.Outcome.retry(describe(e));
            } else if (DatabaseErrors.isUnavailable(e)) {
                log.warn("automation.drain.stopped event={}: the database is unavailable: {}", id, e.toString());
                try {
                    Instant at = clock.instant();
                    tx.execute(s -> events.release(id, token, at, at.plus(RELEASE_WAIT)));
                } catch (RuntimeException ignored) {
                    // The lease runs out and recovers the event instead.
                }
                return false;
            } else {
                log.warn("automation.event.failed event={}: {}", id, e.toString(), e);
                outcome = EventProcessor.Outcome.retry(describe(e));
            }
        }
        finish(id, token, outcome);
        return true;
    }

    private void finish(Long id, int token, EventProcessor.Outcome outcome) {
        Instant at = clock.instant();
        int rows = switch (outcome.kind()) {
            case DONE -> tx.execute(s -> events.finishDone(id, token, at));
            case RETRY -> retry(id, token, outcome.error());
            case YIELD -> tx.execute(s -> events.yieldAt(id, token, outcome.cursor(), at));
            case LOST, FINISHED -> 1;
        };
        if (rows == 0) log.warn("automation.event.lost event={} token={}: lost the lease", id, token);
    }

    /** Another attempt after a growing wait, or given up on once the attempts are used up. */
    private int retry(Long id, int token, String error) {
        AutomationEvent ev = events.findById(id).orElse(null);
        if (ev == null) return 0;
        int attempts = ev.getAttempts();
        if (attempts + 1 >= props.getMaxAttempts()) {
            return dead(id, token, "Gave up after " + (attempts + 1) + " attempts: " + error);
        }
        Instant at = clock.instant();
        Instant next = at.plus(BACKOFF.get(Math.min(attempts, BACKOFF.size() - 1)));
        return tx.execute(s -> events.finishRetry(id, token, at, next,
                Fit.to(error, AutomationEvent.LAST_ERROR_LENGTH)));
    }

    /** Given up: in the same transaction the event's unfinished runs fail and their owners are told. */
    private int dead(Long id, int token, String why) {
        Instant at = clock.instant();
        Integer rows = tx.execute(s -> {
            AutomationEvent ev = events.findById(id).orElse(null);
            if (ev == null) return 0;
            int attempts = Math.max(ev.getAttempts() + 1, 1);
            int n = events.finishDead(id, token, at, attempts, Fit.to(why, AutomationEvent.LAST_ERROR_LENGTH));
            if (n == 1) runner.gaveUp(ev, why);
            return n;
        });
        return rows == null ? 0 : rows;
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** {@code app.automation.node-id}, or the host's name and a random suffix, fitted to its column. */
    static String nodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Fit.to(configured.trim(), AutomationEvent.CLAIMED_BY_LENGTH);
        }
        // The environment's name for the host: asking the resolver could hold up startup for seconds.
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = "node";
        byte[] suffix = new byte[4];
        ThreadLocalRandom.current().nextBytes(suffix);
        return Fit.to(host + "-" + HexFormat.of().formatHex(suffix), AutomationEvent.CLAIMED_BY_LENGTH);
    }
}
