package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The consumer: takes one outbox row, works out what it is for, and settles it (R3).
 *
 * <p>Every database step is its own short transaction and none is open while a rule's action runs.
 * An action writes through services that open their own, and the email path hands work to the mail
 * service, which must never happen with a pooled connection held — the same rule
 * {@code EmailDispatcher} works to, and for the same reason.
 *
 * <p>The row is taken by a conditional update rather than by reading it and writing it back, so a
 * nudge and the sweeper arriving at the same row cannot both run it. Losing the claim is the
 * ordinary case, not an error: it means somebody else has this one.
 *
 * <p>What a failure means is decided by what was thrown. A {@link BadRequestException}, a
 * {@link NotFoundException} or an {@link AccessDeniedException} is the rule saying it cannot act on
 * this record — the filters no longer parse, the record has gone, the customer has no Collection
 * POC — and no number of retries will change that, so it is a skip with the reason on it. Anything
 * else is the app having a bad moment and is retried twice with a wait, then left FAILED for
 * somebody to look at.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationWorker {

    /** Three goes at a row that keeps failing for reasons the rule cannot describe. */
    static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration LATER_RETRY = Duration.ofMinutes(5);

    private final AutomationEventRepository events;
    private final AutomationRuleRepository rules;
    private final AutomationEvents outbox;
    private final AutomationMatcher matcher;
    private final AutomationActions actions;
    private final AutomationJson json;
    private final TransactionTemplate transactions;

    public void process(Long eventId) {
        process(eventId, Instant.now());
    }

    /** @param now the time a retry's wait is measured against: the sweep's own clock when the sweeper drives */
    void process(Long eventId, Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An automation event must be run outside a transaction");
        }
        AutomationEvent event = transactions.execute(status -> claim(eventId, now));
        if (event == null) return;

        try {
            String outcome = event.getRuleId() == null ? fanOut(event, now) : run(event, now);
            transactions.executeWithoutResult(status -> settle(eventId, AutomationEventStatus.DONE, outcome));
        } catch (BadRequestException | NotFoundException | AccessDeniedException e) {
            transactions.executeWithoutResult(status ->
                    settle(eventId, AutomationEventStatus.SKIPPED, e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Automation event {} failed", eventId, e);
            transactions.executeWithoutResult(status -> failed(eventId, e.getMessage(), now));
        }
    }

    /**
     * Takes the row, and hands back a detached copy of it to work from. Detached on purpose: the
     * transaction that read it is over by the time the action runs, and a managed entity outside
     * its persistence context is how lazy loading turns into an exception on a background thread.
     * Null when somebody else has it, or its wait is not over, or it has already been settled.
     */
    private AutomationEvent claim(Long eventId, Instant now) {
        if (events.claim(eventId, now, Instant.now()) == 0) return null;
        return events.findById(eventId).orElse(null);
    }

    // ---- the two kinds of row ------------------------------------------------------------

    /**
     * A fan-out row: turn "this invoice was updated" into one rule row per rule that now applies
     * (R2). It creates nothing itself, which is what makes it safe to run twice — the rule rows it
     * writes are keyed, so a second pass over the same fan-out row writes nothing new.
     */
    private String fanOut(AutomationEvent event, Instant now) {
        List<AutomationRule> applicable = rules.findByEnabledTrueAndEntityTypeAndTriggerOrderByIdAsc(
                event.getEntityType(), event.getTrigger());
        if (applicable.isEmpty()) {
            // The ordinary case on a system with no rules for this kind: nothing to answer for.
            return "No rule watches " + event.getEntityType().noun() + "s for this";
        }
        int queued = 0;
        for (AutomationRule rule : applicable) {
            queued += outbox.enqueueForRule(rule, List.of(event.getEntityId()), now).size();
        }
        return "Queued " + queued + " of " + applicable.size() + " rule(s)";
    }

    /**
     * A rule row: the one place a rule's WHERE is evaluated and its THEN happens. The rule is read
     * afresh here rather than carried on the row, so a rule disabled or rewritten between the
     * queueing and the run acts as it is now, not as it was.
     */
    private String run(AutomationEvent event, Instant now) {
        AutomationRule rule = rules.findById(event.getRuleId()).orElse(null);
        if (rule == null) throw new NotFoundException("This rule has been deleted");
        if (!rule.isEnabled()) throw new BadRequestException("This rule is switched off");
        if (rule.getEntityType() != event.getEntityType()) {
            throw new BadRequestException("This rule no longer watches " + event.getEntityType().noun() + "s");
        }
        if (!matcher.matches(event.getEntityType(), json.filters(rule), event.getEntityId())) {
            throw new BadRequestException("The filters did not match this " + event.getEntityType().noun());
        }
        String outcome = actions.perform(rule, event);
        transactions.executeWithoutResult(status -> recordRun(rule.getId(), now));
        return outcome;
    }

    /**
     * Counts a run on the rule. Read back under its own transaction rather than saving the copy the
     * run worked from, so two rules firing at once do not each write the other's count back.
     */
    private void recordRun(Long ruleId, Instant now) {
        rules.findById(ruleId).ifPresent(rule -> {
            rule.setLastRunAt(now);
            rule.setRunCount(rule.getRunCount() == null ? 1L : rule.getRunCount() + 1);
            rules.save(rule);
        });
    }

    // ---- settling ------------------------------------------------------------------------

    /**
     * Writes what actually happened, with the row locked. It does not check the status first: if
     * the sweeper gave up on this run while it was still going, the run finished all the same and
     * saying so is the truer record.
     *
     * <p>A rule row that skipped also gives its de-duplication key up here, because it is the one
     * outcome that did nothing: the WHERE is evaluated on this thread, long after the key was
     * claimed, and a record the filters refused this morning must still be able to be asked again
     * this afternoon when somebody has changed it (D-72). Why SKIPPED alone, and why that is still
     * safe against a double delivery, is written out on {@link AutomationEvent#releasedKey}. A
     * fan-out row has nothing to release — its key ends in a UUID and claims no bucket.
     */
    private void settle(Long eventId, AutomationEventStatus status, String note) {
        events.findByIdForUpdate(eventId).ifPresent(event -> {
            event.setStatus(status);
            event.setLastError(fit(note));
            event.setNextAttemptAt(null);
            if (status == AutomationEventStatus.SKIPPED && event.getRuleId() != null) {
                event.setIdempotencyKey(AutomationEvent.releasedKey(
                        event.getIdempotencyKey(), event.getId()));
            }
            events.save(event);
        });
    }

    /**
     * Something the rule could not describe went wrong. Two more goes, a minute out and then five,
     * and then it is left FAILED — a row somebody can see rather than a retry that goes on forever.
     * {@code enqueuedAt} is cleared so the sweeper publishes it again when its wait is over; leaving
     * it set would make the row look like one already on its way to a worker. The row is read under
     * its lock, and left alone if the sweeper has settled it since — see the note inside.
     */
    private void failed(Long eventId, String message, Instant now) {
        events.findByIdForUpdate(eventId).ifPresent(event -> {
            // The sweeper may have given up on this run while it was still going. Putting the row
            // back to QUEUED now would run again something it has already told somebody might have
            // happened, which is the one outcome this whole design is built to avoid.
            if (event.getStatus() != AutomationEventStatus.RUNNING) return;
            String error = fit(message == null ? "This rule could not be run" : message);
            if (event.getAttempts() < MAX_ATTEMPTS) {
                event.setStatus(AutomationEventStatus.QUEUED);
                event.setNextAttemptAt(now.plus(event.getAttempts() <= 1 ? FIRST_RETRY : LATER_RETRY));
                event.setEnqueuedAt(null);
            } else {
                event.setStatus(AutomationEventStatus.FAILED);
                event.setNextAttemptAt(null);
            }
            event.setLastError(error);
            events.save(event);
        });
    }

    private static String fit(String note) {
        if (note == null) return null;
        return note.length() <= AutomationEvent.ERROR_MAX
                ? note
                : note.substring(0, AutomationEvent.ERROR_MAX - 1) + "…";
    }
}
