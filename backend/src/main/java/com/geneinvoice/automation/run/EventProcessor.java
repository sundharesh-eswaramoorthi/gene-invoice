package com.geneinvoice.automation.run;

import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.event.EventPoints;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.common.DatabaseErrors;
import com.geneinvoice.common.scope.AsOfPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one claimed event sets off (§5.10.6). A change to a record runs each rule it concerns for that record, at the
 * moment of the change; a customer's CHILDREN event (a rename, a region move) becomes one event per rule concerned,
 * each run over the customer's invoices or payments; a rule's own event (a run by hand) runs that rule over its set.
 * Every event is its own: nothing is merged (D10), so the rule that caused a change is not run for it, and a chain
 * of rules stops at depth 3.
 */
@Component
@Slf4j
public class EventProcessor {

    static final int MAX_DEPTH = 3;

    /** What came of an event: finished, to try again, handed back part way, or no longer this worker's. */
    public record Outcome(Kind kind, String error, Long cursor) {

        public enum Kind {
            DONE, RETRY, YIELD, LOST,
            /** Finished inside the processing transaction itself: nothing is left for the worker to write. */
            FINISHED
        }

        public static Outcome done() {
            return new Outcome(Kind.DONE, null, null);
        }

        public static Outcome retry(String error) {
            return new Outcome(Kind.RETRY, error, null);
        }

        /** Handed back part way, to carry on after record {@code cursor} at its next claim. */
        public static Outcome yielded(Long cursor) {
            return new Outcome(Kind.YIELD, null, cursor);
        }

        public static Outcome lost() {
            return new Outcome(Kind.LOST, null, null);
        }

        public static Outcome finished() {
            return new Outcome(Kind.FINISHED, null, null);
        }
    }

    private final AutomationEventRepository eventRepository;
    private final AutomationRuleRepository ruleRepository;
    private final RuleRunner runner;
    private final EventPoints eventPoints;
    private final Clock clock;
    private final TransactionTemplate tx;

    public EventProcessor(AutomationEventRepository eventRepository, AutomationRuleRepository ruleRepository,
                          RuleRunner runner, EventPoints eventPoints, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.ruleRepository = ruleRepository;
        this.runner = runner;
        this.eventPoints = eventPoints;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The claimed event {@code id}, held with fencing token {@code token}. */
    public Outcome process(Long id, int token) {
        AutomationEvent ev = eventRepository.findById(id).orElse(null);
        if (ev == null) return Outcome.lost();
        if (ev.getRuleId() != null) return forRule(ev, token);
        return switch (ev.getKind()) {
            case AutomationEvent.CREATED -> forRecord(ev, token, List.of(TriggerKind.ENTITY_CREATED,
                    TriggerKind.ENTITY_CREATED_OR_UPDATED));
            case AutomationEvent.UPDATED -> forRecord(ev, token, List.of(TriggerKind.ENTITY_UPDATED,
                    TriggerKind.ENTITY_CREATED_OR_UPDATED));
            case AutomationEvent.CHILDREN -> fanOut(ev, token);
            default -> {
                log.warn("automation.event.unknown event={} kind={}: nothing to run", ev.getId(), ev.getKind());
                yield Outcome.done();
            }
        };
    }

    /** A change to one record: each rule it concerns, isolated from the others; the worst outcome is the event's. */
    private Outcome forRecord(AutomationEvent ev, int token, List<TriggerKind> kinds) {
        AsOfPoint point = eventPoints.pointOf(ev);
        Outcome outcome = Outcome.done();
        for (AutomationRule rule : candidates(ev, List.of(TriggerEntity.valueOf(ev.getEntityType())), kinds)) {
            Outcome one;
            try {
                one = runner.runForRecord(rule, ev, token, point);
            } catch (RuntimeException e) {
                if (!DatabaseErrors.isConcurrency(e) && DatabaseErrors.isUnavailable(e)) throw e;
                log.warn("automation.rule.failed rule={} event={}: {}", rule.getId(), ev.getId(), e.toString());
                one = Outcome.retry(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            outcome = RuleRunner.worse(outcome, one);
        }
        return outcome;
    }

    /**
     * The event a customer's change wrote for all its invoices and payments: one event per rule it concerns, written
     * in the same transaction that finishes it, so a redelivery never makes them twice. The fan-out costs the
     * consumer one row per rule, never the user's save (PC-4).
     */
    private Outcome fanOut(AutomationEvent ev, int token) {
        List<AutomationRule> rules = candidates(ev, List.of(TriggerEntity.INVOICE, TriggerEntity.PAYMENT),
                List.of(TriggerKind.ENTITY_UPDATED, TriggerKind.ENTITY_CREATED_OR_UPDATED));
        Instant now = clock.instant();
        boolean mine = Boolean.TRUE.equals(tx.execute(s -> {
            if (eventRepository.finishDone(ev.getId(), token, now) == 0) return false;
            List<AutomationEvent> perRule = new ArrayList<>();
            for (AutomationRule rule : rules) {
                perRule.add(AutomationEvent.builder()
                        .kind(AutomationEvent.CHILDREN)
                        .entityType(ev.getEntityType())
                        .entityId(ev.getEntityId())
                        .historyTxId(ev.getHistoryTxId())
                        .ruleId(rule.getId())
                        .ruleVersion(rule.getVersion())
                        .changes(ev.getChanges())
                        .origin(ev.getOrigin())
                        .causedByRuleId(ev.getCausedByRuleId())
                        .depth(ev.getDepth())
                        .actorUserId(ev.getActorUserId())
                        .occurredAt(ev.getOccurredAt())
                        .status(AutomationEvent.PENDING)
                        .build());
            }
            eventRepository.saveAll(perRule);
            return true;
        }));
        if (!mine) {
            log.warn("automation.event.lost event={} token={}: lost the lease", ev.getId(), token);
            return Outcome.lost();
        }
        return Outcome.finished();
    }

    /** A rule's own event: a run by hand, a schedule slot, or its share of a customer's CHILDREN event. */
    private Outcome forRule(AutomationEvent ev, int token) {
        AutomationRule rule = ruleRepository.findById(ev.getRuleId()).orElse(null);
        if (rule == null) {
            log.warn("automation.event.rule.missing event={} rule={}", ev.getId(), ev.getRuleId());
            return Outcome.done();
        }
        return runner.runForSet(rule, ev, token);
    }

    /**
     * The rules a change concerns: on since before it happened, triggered by it, and not the rule whose own action
     * made it; none at all once a chain of rules is deeper than {@value #MAX_DEPTH}.
     */
    private List<AutomationRule> candidates(AutomationEvent ev, List<TriggerEntity> entities,
                                            List<TriggerKind> kinds) {
        if (ev.getDepth() > MAX_DEPTH) {
            log.info("automation.event.depth event={} depth={}: no rule runs", ev.getId(), ev.getDepth());
            return List.of();
        }
        return ruleRepository.findEntityCandidates(entities, kinds, ev.getOccurredAt()).stream()
                .filter(r -> !Objects.equals(r.getId(), ev.getCausedByRuleId()))
                .toList();
    }
}
