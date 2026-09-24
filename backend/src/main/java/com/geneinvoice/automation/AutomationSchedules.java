package com.geneinvoice.automation;

import com.geneinvoice.invoice.InvoiceDates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

/**
 * THE SECOND AND THIRD PRODUCERS: the clock, and somebody pressing "run now" (A1, A5).
 *
 * <p>Neither of them consumes anything. Both open a {@link AutomationRun} in status FANNING with
 * a cursor of 0 and hand it to the sweeper, which materialises its pages, plans its steps and
 * works them through exactly the same claim, fence, backoff and settle the event path uses. That
 * is the whole point of this class being short: there is ONE consumer and three doors into it.
 *
 * <p>NO {@code @Scheduled(cron)} IS INTRODUCED HERE OR ANYWHERE. The fire time lives in two
 * columns on the rule and the next firing lives in {@code next_run_at}, for three named reasons:
 * a cron expression fires on EVERY instance at once and this application has no leader election;
 * Spring's cron trigger reads the system clock and would ignore any seam, so a daily rule could
 * not be tested; and a cron trigger has no dedupe key, where
 * {@link AutomationRuleRepository#claimSchedule} is one conditional UPDATE with exactly one
 * winner. Everything is UTC, matching {@link InvoiceDates} (A1).
 *
 * <p>With a 60 s sweep a daily rule fires within a minute of its hour rather than on it. That
 * drift is stated in the rule editor's help text rather than pretended away (A1).
 */
@Component
@Slf4j
public class AutomationSchedules {

    /** Schedules read per tick. A deployment with more due rules than this catches up next tick. */
    static final int TICK_BATCH = 200;

    /** Runs examined per tick for closure; the same paged-and-ordered-by-id habit as the sweep. */
    static final int CLOSE_BATCH = 200;

    /** The two statuses that mean "this rule is still busy", for the overrun check (A5). */
    static final List<RunStatus> LIVE_RUNS = List.of(RunStatus.FANNING, RunStatus.RUNNING);

    /** The two statuses that mean "this run still has work in it" (A5). */
    static final List<StepStatus> UNFINISHED = List.of(StepStatus.QUEUED, StepStatus.RUNNING);

    static final String OVERRUN =
            "The previous run of this rule had not finished, so this slot was skipped";

    private final AutomationRuleRepository rules;
    private final AutomationRunRepository runs;
    private final AutomationStepRepository steps;
    private final TransactionTemplate transactions;

    public AutomationSchedules(AutomationRuleRepository rules, AutomationRunRepository runs,
                               AutomationStepRepository steps, TransactionTemplate transactions) {
        this.rules = rules;
        this.runs = runs;
        this.steps = steps;
        this.transactions = transactions;
    }

    /** The scheduler's entry point. The real work is {@link #tick(Instant)}, which tests call. */
    public void tick() {
        tick(Instant.now());
    }

    /**
     * THE ONLY TIME SEAM, the {@code EmailDispatcher.sweep(Instant)} shape and not a
     * {@code java.time.Clock} bean, matching the three seams the blueprint names (A1, A5).
     *
     * <p>Runs are CLOSED FIRST and schedules claimed second, and the order is load-bearing: a run
     * that finished since the last tick must stop counting as an overrun before this tick decides
     * whether today's slot may open.
     */
    void tick(Instant now) {
        // The claim and the run insert have to be able to commit and to fail independently — a
        // duplicate slot key must bounce off one statement rather than poison a caller's
        // transaction — which is only true outside one (A5).
        outsideATransaction("An automation schedule tick must run outside a transaction");
        closeFinishedRuns(now);
        for (Long id : page(() -> rules.findDueSchedules(now, PageRequest.of(0, TICK_BATCH)))) {
            fireQuietly(id, now);
        }
    }

    /**
     * A run whose last step has finished is DONE.
     *
     * <p>Nothing else in the engine ever writes that status, and it is not book-keeping: the
     * overrun check asks whether this rule has a FANNING or RUNNING run, so a run left open for
     * ever would make every later slot of that rule a SKIPPED_OVERRUN. Closing the run is what
     * lets a daily rule fire on a second day (A5).
     */
    void closeFinishedRuns(Instant now) {
        for (Long id : page(() -> runs.findRunning(PageRequest.of(0, CLOSE_BATCH)))) {
            transactions.executeWithoutResult(s -> close(id, now));
        }
    }

    /**
     * ONE FIRING OF ONE RULE.
     *
     * <p>{@code claimSchedule} advances {@code next_run_at} to the next slot STRICTLY after now in
     * the same statement that tests it, so a rule whose instance was down for three days fires
     * ONCE when it comes back and not three times — the right dunning behaviour, because a
     * customer who was not chased on Monday is chased today, not chased three times today. 1 is
     * this instance; 0 is somebody else who got there first (A1).
     *
     * <p>AND THE CLAIM IS COMPENSATED, because it is a commit of its own. Everything after it
     * happens in separate transactions, and {@link #insert} recovers only from a duplicate key —
     * a reset connection, a statement timeout or a lock timeout used to reach
     * {@link #fireQuietly}'s WARN, which promised a retry that {@code findDueSchedules} could no
     * longer give: the claim had already moved {@code next_run_at} to tomorrow. A daily dunning
     * rule lost its whole population for that day and left one misleading log line behind. The
     * slot is now put back, so the promise in that sentence is true (A5).
     */
    private void fire(Long ruleId, Instant now) {
        AutomationRule rule = rules.findById(ruleId).orElse(null);
        if (rule == null || !rule.live() || !rule.getTriggerKind().scheduled()) return;
        Instant next = rule.getTriggerKind()
                .nextAfter(now, rule.getScheduleDayOfWeek(), rule.getScheduleHourUtc());
        if (next == null) return;

        // Read BEFORE the claim overwrites it: this is the slot to hand back if nothing opens.
        Instant due = rule.getNextRunAt() == null ? now : rule.getNextRunAt();
        Boolean won = transactions.execute(s -> rules.claimSchedule(ruleId, now, next) == 1);
        if (!Boolean.TRUE.equals(won)) return;

        String occasion = slotOf(now);
        LocalDate asOf = InvoiceDates.dayOf(now);
        try {
            // The overrun check is racy and does not need not to be: uk_run_occasion is the actual
            // barrier, and this only decides whether the history SAYS the slot was skipped or the
            // insert says it by refusing. A slot silently dropped is the thing to avoid (A5).
            if (Boolean.TRUE.equals(transactions.execute(s ->
                    runs.existsByRuleIdAndStatusIn(ruleId, LIVE_RUNS)))) {
                log.info("Rule {} is still running its last slot; recording {} as an overrun",
                        ruleId, occasion);
                insert(build(rule, occasion, StepSource.SCHEDULE, asOf, null,
                        RunStatus.SKIPPED_OVERRUN, OVERRUN, now));
                return;
            }
            if (open(rule, occasion, StepSource.SCHEDULE, asOf, null, now) == null) {
                // insert() met a duplicate key and then could not read the winner back. Nothing
                // opened and nothing will: put the slot back rather than let it evaporate (A5).
                release(ruleId, next, due, now, occasion);
            }
        } catch (RuntimeException e) {
            release(ruleId, next, due, now, occasion);
            throw e;
        }
    }

    /**
     * Undo a claim whose run never opened, so the next tick really can try again (A5).
     *
     * <p>Never allowed to throw: it runs on a failure path, and a compensation that replaces the
     * original exception would hide the reason the slot was lost. What it CANNOT recover is the
     * process being stopped between the two commits — for that the slot is genuinely gone and
     * {@code POST /api/automation/rules/{id}/replay} of that day is the remedy, which is why the
     * WARN below names it.
     */
    private void release(Long ruleId, Instant claimed, Instant due, Instant now, String occasion) {
        try {
            Integer put = transactions.execute(s -> rules.releaseSchedule(ruleId, claimed, due, now));
            if (put != null && put == 1) {
                log.warn("Rule {} claimed slot {} and opened no run; the slot was put back",
                        ruleId, occasion);
            } else {
                log.warn("Rule {} claimed slot {} and opened no run, and the schedule has since"
                        + " moved on; replay that day to recover it", ruleId, occasion);
            }
        } catch (RuntimeException e) {
            log.warn("Rule {} lost slot {} and it could not be put back; replay that day to"
                    + " recover it", ruleId, occasion, e);
        }
    }

    /**
     * THE RUN FACTORY, shared by the clock above and by "run now" (A5).
     *
     * <p>A run with status FANNING, an occasion, a source, an as-of date and a cursor of 0 is
     * everything {@code AutomationDispatcher.materialisePage} needs: it finds the records, plans
     * the steps, advances the cursor and flips the run to RUNNING on a short page or at the cap.
     * There is deliberately nothing else to do here — the consumer already exists.
     *
     * @return the run this call opened, or the one that was already there under the same
     *         occasion, which is how a double click and a second instance both get one run
     */
    public AutomationRun open(AutomationRule rule, String occasion, StepSource source,
                              LocalDate asOf, Long requestedByUserId, Instant now) {
        return insert(build(rule, occasion, source, asOf, requestedByUserId,
                RunStatus.FANNING, null, now));
    }

    /** The slot key, bucketed to the hour, so clock skew under an hour is invisible (A1, A5). */
    static String slotOf(Instant now) {
        return "S" + now.truncatedTo(ChronoUnit.HOURS);
    }

    // ---- plumbing -------------------------------------------------------------------------

    private AutomationRun build(AutomationRule rule, String occasion, StepSource source,
                                LocalDate asOf, Long requestedByUserId, RunStatus status,
                                String error, Instant now) {
        AutomationRun run = AutomationRun.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                // FROZEN, exactly as a step's is: a rule edited while this run is still being
                // worked leaves its steps refusing on the version mismatch, and the run row has
                // to say which definition it was opened against (A1, A5).
                .ruleVersion(rule.getDefinitionVersion())
                .occasion(AutomationActions.fit(occasion, AutomationStep.OCCASION_MAX))
                .source(source)
                .asOf(asOf)
                .status(status)
                .requestedByUserId(requestedByUserId)
                .error(AutomationActions.fit(error, AutomationRun.ERROR_MAX))
                .startedAt(now)
                .build();
        if (!status.live()) run.setFinishedAt(now);
        return run;
    }

    /**
     * Let the DATABASE decide whether this occasion is new.
     *
     * <p>{@code uk_run_occasion} is a constraint and not a check-then-act, so two instances
     * claiming the same slot, a retried tick and a double-clicked "run now" all resolve the same
     * way: one insert wins, the other reads back the winner's row. Its own transaction, so the
     * refusal cannot poison a caller's (A5).
     */
    private AutomationRun insert(AutomationRun run) {
        try {
            return transactions.execute(s -> runs.save(run));
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Run {} of rule {} was already opened by somebody else",
                    run.getOccasion(), run.getRuleId());
            return transactions.execute(s ->
                    runs.findByRuleIdAndOccasion(run.getRuleId(), run.getOccasion()).orElse(null));
        }
    }

    private void close(Long runId, Instant now) {
        AutomationRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.RUNNING) return;
        if (steps.existsByRunIdAndStatusIn(runId, UNFINISHED)) return;
        run.setStatus(RunStatus.DONE);
        run.setFinishedAt(now);
        runs.save(run);
    }

    private void fireQuietly(Long ruleId, Instant now) {
        try {
            fire(ruleId, now);
        } catch (RuntimeException e) {
            // One rule that cannot be fired must not stop the other 199 (A1). "The next tick tries
            // again" is only true because fire() hands the claimed slot back before it rethrows;
            // it was a false promise for as long as the claim was left standing (A5).
            log.warn("Firing scheduled rule {} failed; the slot was released and the next tick"
                    + " tries again", ruleId, e);
        }
    }

    private List<Long> page(Supplier<List<Long>> read) {
        List<Long> ids = transactions.execute(s -> read.get());
        return ids == null ? List.of() : ids;
    }

    private static void outsideATransaction(String complaint) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(complaint);
        }
    }
}
