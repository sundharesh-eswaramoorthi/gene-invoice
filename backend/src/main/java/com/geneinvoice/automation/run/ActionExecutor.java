package com.geneinvoice.automation.run;

import com.geneinvoice.automation.action.ActionHandlers;
import com.geneinvoice.automation.action.ActionResult;
import com.geneinvoice.automation.action.ActionSkippedException;
import com.geneinvoice.automation.action.Prepared;
import com.geneinvoice.automation.action.RecordContext;
import com.geneinvoice.automation.action.RecordContexts;
import com.geneinvoice.automation.action.Repeat;
import com.geneinvoice.automation.action.RuleAction;
import com.geneinvoice.automation.eval.RuleDefinitionException;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.RuleDefinition;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Carries out a rule's actions on one record (§5.10.6), each (action, record) in a transaction of its own. The
 * read-only work (the record read as the owner, texts rendered, people resolved and probed) comes first; the
 * customer is locked just before the write, and inside the lock there is only the dedupe re-check, the guard, the
 * write with its audit rows and notices, and the effect row, which commits with what it records, so one dedupe key
 * never acts twice (P2). A failure is classified: some are recorded on the run, a transient one is left for the
 * event's next attempt, and a lost database stops the drain.
 */
@Component
@Slf4j
public class ActionExecutor {

    static final String RECORD_GONE = "The record no longer exists";
    static final String ALREADY_DONE = "Already done for this record";
    public static final String OUTSIDE_REGIONS = "The record is no longer in the rule's regions";
    static final String RESULT_EMAIL = "EMAIL";

    private final RecordContexts recordContexts;
    private final ActionHandlers handlers;
    private final AutomationEffectRepository effects;
    private final CustomerRepository customerRepository;
    private final FailureClassifier classifier;
    private final AutomationFaults faults;
    private final Clock clock;
    private final TransactionTemplate actionTx;

    public ActionExecutor(RecordContexts recordContexts, ActionHandlers handlers, AutomationEffectRepository effects,
                          CustomerRepository customerRepository, FailureClassifier classifier,
                          AutomationFaults faults, Clock clock, PlatformTransactionManager transactionManager) {
        this.recordContexts = recordContexts;
        this.handlers = handlers;
        this.effects = effects;
        this.customerRepository = customerRepository;
        this.classifier = classifier;
        this.faults = faults;
        this.clock = clock;
        this.actionTx = new TransactionTemplate(transactionManager);
        this.actionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What the actions came to on one record: failures left to retry are {@code transients}; {@code emailIds} are the
     * emails queued, which the caller sends once outside any transaction (R15).
     */
    public record Result(int done, int skipped, int failed, int transients, List<Long> emailIds) {}

    /** The effect of one action on one record, as the run counts it. */
    private enum Outcome { DONE, SKIPPED, FAILED }

    /** What one action came to, and the email it queued (to be sent once it committed). */
    private record Acted(Outcome outcome, Long emailId) {}

    /** Every action of the rule on the record, as the owner (the caller runs this inside the owner session). */
    public Result applyAll(RuleDefinition rule, AutomationRun run, AutomationEvent ev, TriggerEntity type, Long id) {
        int done = 0;
        int skipped = 0;
        int failed = 0;
        int transients = 0;
        List<Long> emailIds = new ArrayList<>();
        for (RuleAction a : rule.actions()) {
            if (effects.existsByRunIdAndActionKeyAndTargetTypeAndTargetId(run.getId(), a.key(), type.name(), id)) {
                continue;
            }
            String key = dedupeKey(rule.id(), a, type, id, ev);
            try {
                Acted acted = actionTx.execute(s -> act(rule, run, ev, a, type, id, key));
                faults.afterActionCommitted(rule.id(), a.key(), type.name(), id);
                if (acted.emailId() != null) emailIds.add(acted.emailId());
                switch (acted.outcome()) {
                    case DONE -> done++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }
            } catch (RuntimeException e) {
                switch (classifier.classify(e)) {
                    case DUPLICATE -> {
                        record(rule, run, ev, a, type, id, EffectStatus.SKIPPED, ALREADY_DONE);
                        skipped++;
                    }
                    case SKIP -> {
                        record(rule, run, ev, a, type, id, EffectStatus.SKIPPED, message(e));
                        skipped++;
                    }
                    case PERMANENT -> {
                        record(rule, run, ev, a, type, id, EffectStatus.FAILED, message(e));
                        failed++;
                    }
                    case DEFINITION -> throw e instanceof RuleDefinitionException d ? d
                            : new RuleDefinitionException(message(e));
                    case TRANSIENT -> {
                        log.info("automation.action.transient rule={} action={} {} {}: {}", rule.id(), a.key(),
                                type, id, e.toString());
                        transients++;
                    }
                    case INFRA -> throw e;
                }
            }
        }
        return new Result(done, skipped, failed, transients, emailIds);
    }

    /** One action on one record, inside its transaction. */
    private Acted act(RuleDefinition rule, AutomationRun run, AutomationEvent ev, RuleAction a,
                      TriggerEntity type, Long id, String key) {
        RecordContext rc;
        try {
            rc = recordContexts.load(type, id, rule.id(), rule.name(), rule.zone());
        } catch (NotFoundException e) {
            // Deleted since the event, or no longer the owner's to see; the transaction is rolled back with it.
            throw new ActionSkippedException(RECORD_GONE);
        }
        String label = rc.label();
        // The event matched in the rule's regions (and those a "Run now" asked about); the record may have moved
        // out of them since, and the rule does not act outside them (SC-4).
        Set<Long> regions = ViewScopeHolder.current().regionIds();
        if (regions != null && !regions.contains(rc.regionId())) {
            return new Acted(skipped(rule, run, ev, a, type, id, label, OUTSIDE_REGIONS), null);
        }
        if (effects.existsByDedupeKey(key)) return new Acted(skippedAsDone(rule, run, ev, a, type, id, label), null);
        Prepared prepared = handlers.prepare(a, rc);
        // The owner may not act here (a region they only read, say): a failure for this record alone.
        if (!prepared.problems().isEmpty()) throw new BadRequestException(String.join("; ", prepared.problems()));
        if (handlers.locksCustomer(a)) {
            customerRepository.findByIdForUpdate(rc.customerId());
            faults.afterLock(rule.id(), a.key(), type.name(), id);
        }
        if (effects.existsByDedupeKey(key)) return new Acted(skippedAsDone(rule, run, ev, a, type, id, label), null);
        faults.beforeAction(rule.id(), a.key(), type.name(), id);
        ActionResult result = handlers.perform(a, rc, prepared);
        if (result instanceof ActionResult.Done d) {
            effects.save(AutomationEffect.of(run.getId(), rule.id(), ev.getId(), a.key(), a.type().name(), type.name(),
                    id, label, EffectStatus.DONE, d.resultType(), d.resultId(), null, d.warnings(), key,
                    clock.instant()));
            return new Acted(Outcome.DONE, RESULT_EMAIL.equals(d.resultType()) ? d.resultId() : null);
        }
        ActionResult.Skipped skipped = (ActionResult.Skipped) result;
        effects.save(AutomationEffect.of(run.getId(), rule.id(), ev.getId(), a.key(), a.type().name(), type.name(),
                id, label, EffectStatus.SKIPPED, null, null, skipped.message(), prepared.warnings(), null,
                clock.instant()));
        return new Acted(Outcome.SKIPPED, null);
    }

    /** A once-per-record action that another run already did here: said so on this run, nothing else. */
    private Outcome skippedAsDone(RuleDefinition rule, AutomationRun run, AutomationEvent ev, RuleAction a,
                                  TriggerEntity type, Long id, String label) {
        return skipped(rule, run, ev, a, type, id, label, ALREADY_DONE);
    }

    /** An action that did not act on this record, and why, said on this run; nothing else is written. */
    private Outcome skipped(RuleDefinition rule, AutomationRun run, AutomationEvent ev, RuleAction a,
                            TriggerEntity type, Long id, String label, String message) {
        effects.save(AutomationEffect.of(run.getId(), rule.id(), ev.getId(), a.key(), a.type().name(), type.name(),
                id, label, EffectStatus.SKIPPED, null, null, message, List.of(), null, clock.instant()));
        return Outcome.SKIPPED;
    }

    /** An action that did not act, recorded in a transaction of its own (the action's own was rolled back). */
    private void record(RuleDefinition rule, AutomationRun run, AutomationEvent ev, RuleAction a, TriggerEntity type,
                        Long id, EffectStatus status, String message) {
        actionTx.executeWithoutResult(s -> effects.save(AutomationEffect.of(run.getId(), rule.id(), ev.getId(),
                a.key(), a.type().name(), type.name(), id, null, status, null, null, message, new ArrayList<>(), null,
                clock.instant())));
    }

    /**
     * {@code r{ruleId}:{actionKey}:{TYPE}:{id}}: an action done once per record keeps its key across every run of
     * the rule; one done every time adds the event, since every event is its own (D10).
     */
    static String dedupeKey(Long ruleId, RuleAction a, TriggerEntity type, Long id, AutomationEvent ev) {
        String key = AutomationEffect.oncePerRecordKey(ruleId, a.key(), type.name(), id);
        return a.repeat() == Repeat.EVERY_TIME ? key + ":e" + ev.getId() : key;
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
