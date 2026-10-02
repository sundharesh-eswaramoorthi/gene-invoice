package com.geneinvoice.automation.run;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.eval.RuleDefinitionException;
import com.geneinvoice.automation.eval.RuleEvaluator;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.event.EventPoints;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.automation.rule.RuleDefinition;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.email.EmailDispatcher;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs one rule for one event (§5.10.6), as its owner. For a change to one record, the condition is asked at the
 * moment of the change (D10) and, when it matched, the actions are carried out now; for a set (a run by hand, the
 * children of a customer, a schedule slot) the matches are read page by page at the event's point, the owner checked
 * again for every page. A long set run checkpoints each page and hands its event back when its slice of time is up,
 * carrying on from there at its next claim; an edit to the rule while it runs supersedes it. A resumed rule's REPLAY
 * goes through the changes made while it was suspended, each at its own moment. A run records what it matched and
 * what came of each action; an owner who can no longer act, a rule that can no longer be read, or one acting more
 * often than an hour allows, suspends the rule. Emails a run queues are sent once their actions have committed.
 */
@Component
@Slf4j
public class RuleRunner {

    static final String NOTIF_RUN_FAILED = "AUTOMATION_RUN_FAILED";
    static final String NOT_ACTIVE = "Rule is not active";
    static final String STOPPED = "Stopped: rule is not active";
    static final String SUPERSEDED = "Stopped: the rule was changed while it ran";
    static final String RATE_LIMITED = "Stopped: the rule reached its limit of actions an hour and was suspended";
    static final String SUSPENDED = "Stopped: the rule was suspended";
    static final String REPLAY_LEFT = "; resuming it with replay goes through the changes not reached";

    private final AutomationRuleRepository ruleRepository;
    private final AutomationRunRepository runRepository;
    private final AutomationEffectRepository effectRepository;
    private final AutomationEventRepository eventRepository;
    private final RuleService ruleService;
    private final RuleEvaluator evaluator;
    private final OwnerSession ownerSession;
    private final ActionExecutor actionExecutor;
    private final EventPoints eventPoints;
    private final EmailDispatcher emailDispatcher;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final AutomationProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public RuleRunner(AutomationRuleRepository ruleRepository, AutomationRunRepository runRepository,
                      AutomationEffectRepository effectRepository, AutomationEventRepository eventRepository,
                      RuleService ruleService, RuleEvaluator evaluator, OwnerSession ownerSession,
                      ActionExecutor actionExecutor, EventPoints eventPoints, EmailDispatcher emailDispatcher,
                      NotificationService notificationService, UserRepository userRepository,
                      AutomationProperties props, Clock clock, PlatformTransactionManager transactionManager) {
        this.ruleRepository = ruleRepository;
        this.runRepository = runRepository;
        this.effectRepository = effectRepository;
        this.eventRepository = eventRepository;
        this.ruleService = ruleService;
        this.evaluator = evaluator;
        this.ownerSession = ownerSession;
        this.actionExecutor = actionExecutor;
        this.eventPoints = eventPoints;
        this.emailDispatcher = emailDispatcher;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The event a worker holds, with the fencing token of its claim: every write of the run is fenced by it. */
    private record Lease(Long eventId, int token) {}

    // ---- one record -----------------------------------------------------------------------------------------

    /**
     * The rule for a change to one record, its condition asked at {@code point} (live when null), the event held
     * with {@code token}.
     */
    public EventProcessor.Outcome runForRecord(AutomationRule rule, AutomationEvent ev, int token, AsOfPoint point) {
        return runForRecord(rule, ev, new Lease(ev.getId(), token), point, null);
    }

    /**
     * The rule for the change {@code ev}, while {@code lease} is held (the change's own event, or the REPLAY event
     * going through it: then the run is the replay's, {@code replay}).
     */
    private EventProcessor.Outcome runForRecord(AutomationRule rule, AutomationEvent ev, Lease lease,
                                                AsOfPoint point, AutomationEvent replay) {
        Optional<AutomationRun> existing = runRepository.findByRuleIdAndEventId(rule.getId(), ev.getId());
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) return EventProcessor.Outcome.done();
        TriggerEntity type = TriggerEntity.valueOf(ev.getEntityType());
        RuleDefinition def;
        try {
            def = definition(rule);
        } catch (RuntimeException e) {
            return definitionFailed(rule, ev, lease, "The rule cannot be read: " + e.getMessage(), replay);
        }
        if (recentActions(rule.getId()) >= props.getMaxActionsPerRulePerHour()) {
            rateLimited(rule.getId());
            existing.ifPresent(r -> finish(r.getId(), rule.getId(), lease, RunStatus.SKIPPED, RATE_LIMITED, null));
            return EventProcessor.Outcome.done();
        }
        try {
            return ownerSession.call(def, ev, existing.map(AutomationRun::getId).orElse(null), () -> {
                boolean matched = AsOf.with(point, () -> evaluator.matches(def, ev.getEntityId()));
                if (!matched) {
                    existing.ifPresent(r -> finish(r.getId(), rule.getId(), lease, RunStatus.SKIPPED,
                            "Did not match", null));
                    return EventProcessor.Outcome.done();
                }
                AutomationRun run = start(rule, ev, 1, replay);
                ActionExecutor.Result result = OwnerSession.forRun(run.getId(),
                        () -> actionExecutor.applyAll(def, run, ev, type, ev.getEntityId()));
                // Sent now that every action has committed, outside any transaction (R15).
                emailDispatcher.dispatchAll(result.emailIds());
                return settle(run.getId(), rule.getId(), lease, result.transients(),
                        point == null ? EventPoints.NOT_RECORDED : null);
            });
        } catch (OwnerUnavailableException e) {
            ruleService.suspend(rule.getId(), e.reason(), e.getMessage());
            existing.ifPresent(r -> finish(r.getId(), rule.getId(), lease, RunStatus.SKIPPED, e.getMessage(), null));
            return EventProcessor.Outcome.done();
        } catch (RuleDefinitionException e) {
            return definitionFailed(rule, ev, lease, e.getMessage(), replay);
        }
    }

    // ---- a set ----------------------------------------------------------------------------------------------

    /** What a set run has got through so far. */
    private static final class Progress {
        long after;
        int matched;
        boolean truncated;
        boolean last;
        boolean rateLimited;
        int transients;
    }

    /**
     * The rule over a set of records: those asked for by hand, the invoices and payments of a customer (CHILDREN),
     * or everything it matches (a schedule slot, a run by hand), all as of the event's point; or, for a REPLAY, the
     * changes made while the rule was suspended. A disabled rule is not run, except by hand: a rule nobody switched on
     * can still be run on purpose (PC-6). An event handed back part way carries on from its cursor, unless the rule
     * was changed meanwhile.
     */
    public EventProcessor.Outcome runForSet(AutomationRule rule, AutomationEvent ev, int token) {
        Lease lease = new Lease(ev.getId(), token);
        boolean isReplay = AutomationEvent.REPLAY.equals(ev.getKind());
        AutomationRun run = runRepository.findByRuleIdAndEventId(rule.getId(), ev.getId())
                .orElseGet(() -> ensure(rule, ev, RunStatus.QUEUED, 0, isReplay ? ev : null));
        if (run.getStatus().isTerminal()) return EventProcessor.Outcome.done();
        boolean manual = AutomationEvent.MANUAL.equals(ev.getKind());
        boolean resuming = ev.getCursorId() != null;
        if (!active(rule, ev, manual)) {
            if (isReplay) {
                Progress p = new Progress();
                p.after = resuming ? ev.getCursorId() : 0;
                p.matched = resuming ? run.getMatched() : 0;
                return replayStopped(rule, ev, lease, run.getId(), p);
            }
            finish(run.getId(), rule.getId(), lease, RunStatus.SKIPPED, NOT_ACTIVE, null);
            return EventProcessor.Outcome.done();
        }
        if (resuming && run.getRuleVersion() != null && !run.getRuleVersion().equals(rule.getVersion())) {
            finish(run.getId(), rule.getId(), lease, RunStatus.SUPERSEDED, SUPERSEDED, null);
            return EventProcessor.Outcome.done();
        }
        RuleDefinition def;
        try {
            def = definition(rule);
        } catch (RuntimeException e) {
            return definitionFailed(rule, ev, lease, "The rule cannot be read: " + e.getMessage(), null);
        }
        Long runId = run.getId();
        boolean holds = Boolean.TRUE.equals(tx.execute(s -> {
            if (!hold(s, lease)) return false;
            AutomationRun r = runRepository.findById(runId).orElseThrow();
            if (r.getStartedAt() == null) r.setStartedAt(clock.instant());
            r.setStatus(RunStatus.RUNNING);
            if (!resuming) {
                // From its first record: what it did is not done twice (its effects say so), and it counts its
                // matches afresh, as of the rule it starts with.
                r.setRuleVersion(rule.getVersion());
                r.setMatched(0);
                r.setTruncated(false);
            }
            return true;
        }));
        if (!holds) return lost(lease);
        AutomationRun running = runRepository.findById(runId).orElseThrow();
        Progress p = new Progress();
        p.after = resuming ? ev.getCursorId() : 0;
        p.matched = resuming ? running.getMatched() : 0;
        long sliceStart = System.nanoTime();
        if (isReplay) return replay(rule, def, ev, lease, running, p, sliceStart);

        AsOfPoint point = eventPoints.pointOf(ev);
        RuleEvaluator.Restriction restriction = restriction(ev);
        try {
            while (true) {
                page(def, ev, running, point, restriction, p);
                if (p.rateLimited) return stoppedByLimit(rule.getId(), runId, lease, p);
                // A page with an action left to try again is not checkpointed: the retry does it again, and only
                // what is not settled acts.
                if (p.transients > 0) return retry(p.transients);
                if (!checkpoint(runId, lease, p)) return lost(lease);
                if (p.last || p.truncated) break;
                EventProcessor.Outcome stop = stillRunning(rule.getId(), running.getRuleVersion(), ev, manual,
                        runId, lease, p, sliceStart);
                if (stop != null) return stop;
            }
        } catch (OwnerUnavailableException e) {
            ruleService.suspend(rule.getId(), e.reason(), e.getMessage());
            finish(runId, rule.getId(), lease, p.matched > 0 ? RunStatus.PARTIAL : RunStatus.SKIPPED,
                    e.getMessage(), p);
            return EventProcessor.Outcome.done();
        } catch (RuleDefinitionException e) {
            return definitionFailed(rule, ev, lease, e.getMessage(), null);
        }
        String message = p.truncated ? "Stopped after " + def.maxRecordsPerRun() + " records, the rule's limit"
                : point == null && !manual ? EventPoints.NOT_RECORDED : null;
        return settle(runId, rule.getId(), lease, 0, message);
    }

    /**
     * One page of a set, in its own owner session (the owner is checked again for every page), evaluated at
     * {@code point}: each match up to the rule's limit acted on, unless the rule has used up its actions for the
     * hour; the page's emails sent once its actions committed.
     */
    private void page(RuleDefinition def, AutomationEvent ev, AutomationRun run, AsOfPoint point,
                      RuleEvaluator.Restriction restriction, Progress p) {
        List<Long> emails = new ArrayList<>();
        ownerSession.call(def, ev, run.getId(), () -> {
            List<Long> page = AsOf.with(point, () -> evaluator.matchingPage(def, p.after, props.getPageSize(),
                    restriction));
            p.last = page.size() < props.getPageSize();
            long recent = recentActions(def.id());
            for (Long id : page) {
                if (p.matched >= def.maxRecordsPerRun()) {
                    p.truncated = true;
                    break;
                }
                if (recent >= props.getMaxActionsPerRulePerHour()) {
                    p.rateLimited = true;
                    break;
                }
                p.matched++;
                ActionExecutor.Result result = actionExecutor.applyAll(def, run, ev, def.entity(), id);
                p.transients += result.transients();
                recent += result.done();
                emails.addAll(result.emailIds());
                p.after = id;
            }
            return null;
        });
        emailDispatcher.dispatchAll(emails);
    }

    /**
     * Between pages: whether the run carries on. A rule stopped meanwhile ends it (what it did stands); a rule
     * changed meanwhile supersedes it (the next run uses the new rule); a slice of time used up hands the event back,
     * to carry on from the checkpoint. Null: carry on now.
     */
    private EventProcessor.Outcome stillRunning(Long ruleId, Long runVersion, AutomationEvent ev, boolean manual,
                                                Long runId, Lease lease, Progress p, long sliceStart) {
        AutomationRule current = ruleRepository.findById(ruleId).orElse(null);
        if (current == null || !active(current, ev, manual)) {
            finish(runId, ruleId, lease, p.matched > 0 ? RunStatus.PARTIAL : RunStatus.SKIPPED, STOPPED, p);
            return EventProcessor.Outcome.done();
        }
        if (!Objects.equals(current.getVersion(), runVersion)) {
            finish(runId, ruleId, lease, RunStatus.SUPERSEDED, SUPERSEDED, p);
            return EventProcessor.Outcome.done();
        }
        if (Duration.ofNanos(System.nanoTime() - sliceStart).toMillis() >= props.getSliceBudgetMs()) {
            return EventProcessor.Outcome.yielded(p.after);
        }
        return null;
    }

    /** The page's counters and the cursor after it, in one fenced transaction: false when the lease was lost. */
    private boolean checkpoint(Long runId, Lease lease, Progress p) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!hold(s, lease)) return false;
            runRepository.setProgress(runId, p.matched, p.truncated);
            eventRepository.checkpoint(lease.eventId(), lease.token(), p.after, clock.instant());
            return true;
        }));
    }

    /** The rule used up its actions for the hour part way through a set: it is suspended, and the run ends. */
    private EventProcessor.Outcome stoppedByLimit(Long ruleId, Long runId, Lease lease, Progress p) {
        rateLimited(ruleId);
        finish(runId, ruleId, lease, p.matched > 0 ? RunStatus.PARTIAL : RunStatus.SKIPPED, RATE_LIMITED, p);
        return EventProcessor.Outcome.done();
    }

    /** The rule as the engine reads it, its regions loaded in a transaction of their own. */
    private RuleDefinition definition(AutomationRule rule) {
        return tx.execute(s -> ruleService.definitionOf(ruleRepository.findById(rule.getId()).orElseThrow()));
    }

    /**
     * A rule still acting for the event: standing, not suspended, and on (a run by hand needs it only standing). A
     * customer's change is the rule's only if the rule was on when it happened ({@code active_since}, §3.6): a
     * per-rule CHILDREN event still waiting when the rule was switched off and on again, or resumed without replay,
     * is backlog and not acted on.
     */
    private static boolean active(AutomationRule rule, AutomationEvent ev, boolean manual) {
        if (rule.isDeleted() || rule.isSuspended() || !(manual || rule.isEnabled())) return false;
        return !AutomationEvent.CHILDREN.equals(ev.getKind())
                || rule.getActiveSince() != null && !ev.getOccurredAt().isBefore(rule.getActiveSince());
    }

    private static RuleEvaluator.Restriction restriction(AutomationEvent ev) {
        if (AutomationEvent.MANUAL.equals(ev.getKind())) {
            Set<Long> ids = OwnerSession.ids(ev.getRecordIds());
            return ids == null ? RuleEvaluator.Restriction.NONE : RuleEvaluator.Restriction.ids(List.copyOf(ids));
        }
        if (AutomationEvent.CHILDREN.equals(ev.getKind())) return RuleEvaluator.Restriction.customer(ev.getEntityId());
        return RuleEvaluator.Restriction.NONE;
    }

    // ---- replay (§5.10.7, PC-18) ------------------------------------------------------------------------------

    /**
     * A resumed rule going through the changes made while it was suspended ({@code [scheduled_for, occurred_at)} of
     * the REPLAY event): each change it would have been asked about is run as it would have been then, at its own
     * moment, its run keyed by the change, so a change already acted on (live, or by an earlier replay) is not acted
     * on again. The REPLAY run counts the changes it went through; what they did is on their own runs. A rule
     * suspended part way (a change took it past its hourly limit, its owner can no longer act) stops the replay
     * before the change it was suspended at, leaving the rest to the next resume.
     */
    private EventProcessor.Outcome replay(AutomationRule rule, RuleDefinition def, AutomationEvent ev, Lease lease,
                                          AutomationRun run, Progress p, long sliceStart) {
        while (true) {
            List<AutomationEvent> page = replayable(def.entity(), def.triggerKind(), ev, p.after,
                    props.getPageSize());
            for (AutomationEvent past : page) {
                if (replayable(rule, past)) {
                    EventProcessor.Outcome o = AutomationEvent.CHILDREN.equals(past.getKind())
                            ? replayChildren(rule, def, past, ev, lease)
                            : runForRecord(rule, past, lease, eventPoints.pointOf(past), ev);
                    // Lost, or an action to try again: the replay carries on from its last checkpoint.
                    if (o.kind() != EventProcessor.Outcome.Kind.DONE) return o;
                    // A change that suspended the rule was not gone through: the replay stops before it.
                    AutomationRule current = ruleRepository.findById(rule.getId()).orElse(null);
                    if (current == null || !active(current, ev, false)) {
                        return replayStopped(current, ev, lease, run.getId(), p);
                    }
                    p.matched++;
                }
                p.after = past.getId();
            }
            if (!checkpoint(run.getId(), lease, p)) return lost(lease);
            if (page.size() < props.getPageSize()) break;
            AutomationRule current = ruleRepository.findById(rule.getId()).orElse(null);
            if (current == null || !active(current, ev, false)) {
                return replayStopped(current, ev, lease, run.getId(), p);
            }
            EventProcessor.Outcome stop = stillRunning(rule.getId(), run.getRuleVersion(), ev, false, run.getId(),
                    lease, p, sliceStart);
            if (stop != null) return stop;
        }
        StringBuilder message = new StringBuilder("Replayed " + p.matched
                + (p.matched == 1 ? " change" : " changes") + " made while the rule was suspended");
        Instant kept = clock.instant().minus(Duration.ofDays(props.getEventRetentionDays()));
        if (ev.getScheduledFor().isBefore(kept)) {
            message.append("; changes before ").append(LocalDate.ofInstant(kept, ZoneOffset.UTC))
                    .append(" are no longer kept and could not be replayed");
        }
        return settle(run.getId(), rule.getId(), lease, 0, message.toString());
    }

    /** The next changes of the REPLAY event's window after {@code afterId}, oldest first. */
    private List<AutomationEvent> replayable(TriggerEntity entity, TriggerKind trigger, AutomationEvent replay,
                                             long afterId, int size) {
        return eventRepository.findReplayable(entity.name(), RuleService.changeKinds(trigger),
                RuleService.asksAboutChildren(trigger, entity), replay.getScheduledFor(), replay.getOccurredAt(),
                afterId, PageRequest.of(0, size));
    }

    /**
     * A replay whose rule stopped acting before it got through the window. A suspension (its hourly limit, its owner,
     * its definition) takes the changes not reached back into the suspension, so resuming with replay again goes
     * through them; a rule switched off or deleted just stops. The run ends with how far it got.
     */
    private EventProcessor.Outcome replayStopped(AutomationRule current, AutomationEvent replay, Lease lease,
                                                 Long runId, Progress p) {
        Long ruleId = replay.getRuleId();
        String message = STOPPED;
        if (current != null && !current.isDeleted() && current.isSuspended()) {
            List<AutomationEvent> next = replayable(current.getEntityType(), current.getTriggerKind(), replay,
                    p.after, 1);
            if (!next.isEmpty()) {
                Instant from = next.get(0).getOccurredAt();
                tx.executeWithoutResult(s -> ruleRepository.suspendedSince(ruleId, from));
            }
            message = (current.getSuspendedReason() == SuspendReason.RATE_LIMITED ? RATE_LIMITED : SUSPENDED)
                    + REPLAY_LEFT;
        }
        finish(runId, ruleId, lease, p.matched > 0 ? RunStatus.PARTIAL : RunStatus.SKIPPED, message, p);
        return EventProcessor.Outcome.done();
    }

    /**
     * Whether the rule would have been asked about this change: not one its own action made, not past the depth a
     * chain of rules stops at, and a customer's change not already handed to the rule as an event of its own.
     */
    private boolean replayable(AutomationRule rule, AutomationEvent past) {
        if (past.getDepth() > EventProcessor.MAX_DEPTH || Objects.equals(past.getCausedByRuleId(), rule.getId())) {
            return false;
        }
        return !AutomationEvent.CHILDREN.equals(past.getKind())
                || !eventRepository.fannedOutTo(rule.getId(), past.getEntityId(), past.getHistoryTxId());
    }

    /** A customer's change replayed: the rule over the customer's invoices or payments, as they were then. */
    private EventProcessor.Outcome replayChildren(AutomationRule rule, RuleDefinition def, AutomationEvent parent,
                                                  AutomationEvent replay, Lease lease) {
        Optional<AutomationRun> existing = runRepository.findByRuleIdAndEventId(rule.getId(), parent.getId());
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) return EventProcessor.Outcome.done();
        AsOfPoint point = eventPoints.pointOf(parent);
        AutomationRun run = start(rule, parent, 0, replay);
        Progress p = new Progress();
        try {
            do {
                page(def, parent, run, point, RuleEvaluator.Restriction.customer(parent.getEntityId()), p);
                if (p.rateLimited) return stoppedByLimit(rule.getId(), run.getId(), lease, p);
                if (p.transients > 0) return retry(p.transients);
            } while (!p.last && !p.truncated);
        } catch (OwnerUnavailableException e) {
            ruleService.suspend(rule.getId(), e.reason(), e.getMessage());
            finish(run.getId(), rule.getId(), lease, p.matched > 0 ? RunStatus.PARTIAL : RunStatus.SKIPPED,
                    e.getMessage(), p);
            return EventProcessor.Outcome.done();
        } catch (RuleDefinitionException e) {
            return definitionFailed(rule, parent, lease, e.getMessage(), replay);
        }
        if (!checkpointRun(run.getId(), lease, p)) return lost(lease);
        return settle(run.getId(), rule.getId(), lease, 0, point == null ? EventPoints.NOT_RECORDED : null);
    }

    private boolean checkpointRun(Long runId, Lease lease, Progress p) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!hold(s, lease)) return false;
            runRepository.setProgress(runId, p.matched, p.truncated);
            return true;
        }));
    }

    // ---- the hourly limit -------------------------------------------------------------------------------------

    /** The actions the rule took in the last hour. */
    private long recentActions(Long ruleId) {
        return effectRepository.countByRuleIdAndStatusAndCreatedAtAfter(ruleId, EffectStatus.DONE,
                clock.instant().minus(Duration.ofHours(1)));
    }

    /** A rule acting more often than an hour allows is stopped, so a mistake cannot flood anyone (§5.10.6). */
    private void rateLimited(Long ruleId) {
        ruleService.suspend(ruleId, SuspendReason.RATE_LIMITED, "The rule took "
                + props.getMaxActionsPerRulePerHour() + " actions in the last hour, the most allowed, so it was "
                + "stopped; resume it if that is expected");
    }

    // ---- runs -----------------------------------------------------------------------------------------------

    /**
     * The run of the rule for the event, made RUNNING (or found, and made RUNNING again) with {@code matched}; a run
     * made by a replay says so.
     */
    private AutomationRun start(AutomationRule rule, AutomationEvent ev, int matched, AutomationEvent replay) {
        AutomationRun run = ensure(rule, ev, RunStatus.RUNNING, matched, replay);
        if (run.getStatus() == RunStatus.RUNNING && run.getMatched() == matched) return run;
        return tx.execute(s -> {
            AutomationRun r = runRepository.findById(run.getId()).orElseThrow();
            r.setStatus(RunStatus.RUNNING);
            r.setMatched(matched);
            if (r.getStartedAt() == null) r.setStartedAt(clock.instant());
            return runRepository.save(r);
        });
    }

    /**
     * The run of the rule for the event, made in a transaction of its own; when another worker made it first the
     * insert's unique key refuses it, and it is read instead (outside that transaction, §5.9.1). A run a replay makes
     * is a REPLAY run, asked for by whoever resumed the rule.
     */
    private AutomationRun ensure(AutomationRule rule, AutomationEvent ev, RunStatus status, int matched,
                                 AutomationEvent replay) {
        try {
            return newTx.execute(s -> runRepository.findByRuleIdAndEventId(rule.getId(), ev.getId())
                    .orElseGet(() -> {
                        AutomationRun run = AutomationRun.of(rule.getId(), rule.getVersion(), ev, status,
                                replay == null ? null : replay.getActorUserId(), clock.instant());
                        if (replay != null) run.setTriggerType(AutomationEvent.REPLAY);
                        run.setMatched(matched);
                        return runRepository.saveAndFlush(run);
                    }));
        } catch (DataIntegrityViolationException e) {
            return runRepository.findByRuleIdAndEventId(rule.getId(), ev.getId()).orElseThrow(() -> e);
        }
    }

    private static EventProcessor.Outcome retry(int transients) {
        return EventProcessor.Outcome.retry(transients + (transients == 1 ? " action" : " actions")
                + " to try again");
    }

    /**
     * Counts what came of the run from its effects and finishes it, unless an action is left to try again: then it
     * stays RUNNING and the event is retried (only what is not settled is done again). Fenced: a worker that lost
     * the event's lease finishes nothing.
     */
    private EventProcessor.Outcome settle(Long runId, Long ruleId, Lease lease, int transients, String message) {
        if (transients > 0) return retry(transients);
        boolean held = Boolean.TRUE.equals(tx.execute(s -> {
            if (!hold(s, lease)) return false;
            AutomationRun run = runRepository.findById(runId).orElseThrow();
            counters(run);
            RunStatus status = run.getFailed() > 0 && run.getDone() == 0 && run.getSkipped() == 0 ? RunStatus.FAILED
                    : run.getFailed() > 0 ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
            run.finish(status, message, clock.instant());
            runRepository.save(run);
            ruleRepository.recordLastRun(ruleId, runId, run.getFinishedAt(), status);
            return true;
        }));
        return held ? EventProcessor.Outcome.done() : lost(lease);
    }

    /**
     * The event's lease, renewed inside the current transaction, which then holds the event's row until it commits;
     * when the worker has lost the lease the transaction is rolled back and false returned. Clears the persistence
     * context, so it comes before anything the transaction reads.
     */
    private boolean hold(TransactionStatus s, Lease lease) {
        Instant now = clock.instant();
        if (eventRepository.heartbeat(lease.eventId(), lease.token(), now,
                now.plusSeconds(props.getLeaseSeconds())) == 1) {
            return true;
        }
        s.setRollbackOnly();
        return false;
    }

    private static EventProcessor.Outcome lost(Lease lease) {
        log.warn("automation.event.lost event={} token={}: lost the lease", lease.eventId(), lease.token());
        return EventProcessor.Outcome.lost();
    }

    private void counters(AutomationRun run) {
        int done = 0;
        int skipped = 0;
        int failed = 0;
        for (Object[] row : effectRepository.countByStatus(run.getId())) {
            int n = ((Number) row[1]).intValue();
            switch ((EffectStatus) row[0]) {
                case DONE -> done = n;
                case SKIPPED -> skipped = n;
                case FAILED -> failed = n;
            }
        }
        run.setDone(done);
        run.setSkipped(skipped);
        run.setFailed(failed);
    }

    /**
     * Ends a run that did nothing more: skipped, stopped part way (with how far it got, {@code p}), or superseded.
     * Fenced like {@link #settle}.
     */
    private void finish(Long runId, Long ruleId, Lease lease, RunStatus status, String message, Progress p) {
        tx.executeWithoutResult(s -> {
            if (!hold(s, lease)) return;
            AutomationRun run = runRepository.findById(runId).orElseThrow();
            if (run.getStatus().isTerminal()) return;
            counters(run);
            if (p != null) {
                run.setMatched(p.matched);
                run.setTruncated(p.truncated);
            }
            run.finish(status, message, clock.instant());
            runRepository.save(run);
            ruleRepository.recordLastRun(ruleId, runId, run.getFinishedAt(), status);
        });
    }

    /** The rule can no longer be evaluated or carried out as written: it is suspended and the run FAILED. */
    private EventProcessor.Outcome definitionFailed(AutomationRule rule, AutomationEvent ev, Lease lease,
                                                    String message, AutomationEvent replay) {
        ruleService.suspend(rule.getId(), SuspendReason.INVALID_DEFINITION, message);
        AutomationRun run = ensure(rule, ev, RunStatus.RUNNING, 0, replay);
        tx.executeWithoutResult(s -> {
            if (!hold(s, lease)) return;
            AutomationRun r = runRepository.findById(run.getId()).orElseThrow();
            if (r.getStatus().isTerminal()) return;
            counters(r);
            r.finish(RunStatus.FAILED, message, clock.instant());
            runRepository.save(r);
            ruleRepository.recordLastRun(rule.getId(), r.getId(), r.getFinishedAt(), RunStatus.FAILED);
        });
        return EventProcessor.Outcome.done();
    }

    /**
     * The event was given up on (§5.10.6): inside the transaction that marks it DEAD, its unfinished runs FAIL and
     * each rule's owner is told once.
     */
    public void gaveUp(AutomationEvent ev, String why) {
        Instant now = clock.instant();
        String message = Fit.to(why, AutomationRun.MESSAGE_MAX);
        Set<Long> rules = new LinkedHashSet<>();
        for (AutomationRun run : runRepository.findUnfinishedByEventId(ev.getId())) {
            counters(run);
            run.finish(RunStatus.FAILED, message, now);
            runRepository.save(run);
            ruleRepository.recordLastRun(run.getRuleId(), run.getId(), now, RunStatus.FAILED);
            rules.add(run.getRuleId());
        }
        if (rules.isEmpty() && ev.getRuleId() != null) rules.add(ev.getRuleId());
        for (AutomationRule rule : ruleRepository.findAllById(rules)) {
            userRepository.findById(rule.getOwnerUserId()).filter(User::isActive).ifPresent(owner ->
                    notificationService.notify(owner.getId(), NOTIF_RUN_FAILED,
                            Fit.to("Rule failed: " + rule.getName(), 200), message,
                            "/automation/" + rule.getId()));
        }
    }

    /** Several rules for one event: the worst of their outcomes decides the event's. */
    static EventProcessor.Outcome worse(EventProcessor.Outcome a, EventProcessor.Outcome b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(EventProcessor.Outcome o) {
        return switch (o.kind()) {
            case DONE -> 0;
            case RETRY -> 1;
            default -> 2;
        };
    }
}
