package com.geneinvoice.automation.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.action.RuleActions;
import com.geneinvoice.automation.action.SendEmailAction;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.AutomationRunRepository;
import com.geneinvoice.automation.run.OwnerSession;
import com.geneinvoice.automation.run.OwnerUnavailableException;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.automation.schedule.ScheduleCalculator;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.ConditionJson;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.email.EmailText;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.connection.GmailStatus;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rules (A1–A5, §5.10): made, changed, turned on and off and deleted by the people allowed to, each change kept as a
 * revision, read as of a date from those revisions. A rule runs as its owner (P3), so whoever makes it act is its
 * owner: an administrator who is not must take it over first, and says so (§5.10.7, SC-2).
 */
@Service
@RequiredArgsConstructor
public class RuleService {

    public static final String ENTITY = "RULE";
    public static final String NOTIF_SUSPENDED = "AUTOMATION_RULE_SUSPENDED";
    static final int MAX_RUN_RECORDS = 50;
    static final int MAX_RUN_AHEAD_DAYS = 90;
    static final int DETAIL_MAX = 500;
    static final String TAKE_OVER = "This makes you the rule's owner; it will act with your access. "
            + "Confirm to go ahead";

    private final AutomationRuleRepository repository;
    private final AutomationRuleRevisionRepository revisionRepository;
    private final AutomationRunRepository runRepository;
    private final AutomationEventRepository eventRepository;
    private final OwnerSession ownerSession;
    private final NotificationService notificationService;
    private final RuleValidator validator;
    private final RuleVisibility visibility;
    private final RuleAsOfLists asOfLists;
    private final TableQueryExecutor queryExecutor;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final GmailConnectionService gmailConnections;
    private final MailTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ---- reads ----------------------------------------------------------------------------------------------

    /** The rules list: rules the caller sees, in the regions asked about; as of a date, from their revisions. */
    @Transactional(readOnly = true)
    public PageResponse<RuleDtos.RuleSummary> page(TableQuery query) {
        ScopeResolver.Scope scope = scope();
        AsOfPoint point = AsOf.current();
        if (point != null) {
            RuleAsOfLists.Page page = asOfLists.page(query, point);
            Map<Long, User> owners = owners(page.content().stream().map(RuleDtos.RuleSnapshot::ownerUserId).toList());
            return PageResponse.of(page.content().stream().map(s -> summary(s, owners)).toList(), query,
                    page.total(), scope);
        }
        var result = queryExecutor.run(AutomationRule.class, TableSchemas.AUTOMATION_RULES, query,
                scope.predicates(), List.of());
        Map<Long, User> owners = owners(result.content().stream().map(AutomationRule::getOwnerUserId).toList());
        return PageResponse.of(result.content().stream().map(r -> summary(r, owners)).toList(), query,
                result.total(), scope);
    }

    /** The tiles over the rules list: how many are on, off and suspended, and runs that failed in the last day. */
    @Transactional(readOnly = true)
    public RuleDtos.SummaryDto summary(TableQuery query) {
        AsOfPoint point = AsOf.current();
        Instant until = point == null ? clock.instant() : point.instant();
        long active = 0;
        long disabled = 0;
        long suspended = 0;
        List<Long> ids = new ArrayList<>();
        if (point != null) {
            TableQuery all = new TableQuery(0, Integer.MAX_VALUE, "id", true, query.filters());
            for (RuleDtos.RuleSnapshot s : asOfLists.page(all, point).content()) {
                ids.add(s.id());
                switch (status(s.enabled(), s.suspendedReason())) {
                    case "ACTIVE" -> active++;
                    case "DISABLED" -> disabled++;
                    default -> suspended++;
                }
            }
        } else {
            List<PredicateFactory> scope = scope().predicates();
            TableQuery all = new TableQuery(0, Integer.MAX_VALUE, "id", true, query.filters());
            List<Long> found = queryExecutor.ids(AutomationRule.class, TableSchemas.AUTOMATION_RULES, all, scope,
                    TableQueryExecutor.BULK_ID_LIMIT);
            for (AutomationRule r : repository.findAllById(found)) {
                ids.add(r.getId());
                switch (status(r.isEnabled(), name(r.getSuspendedReason()))) {
                    case "ACTIVE" -> active++;
                    case "DISABLED" -> disabled++;
                    default -> suspended++;
                }
            }
        }
        long failed = ids.isEmpty() ? 0 : runRepository.countFailed(ids, until.minus(Duration.ofHours(24)), until);
        return new RuleDtos.SummaryDto(active, disabled, suspended, failed);
    }

    /**
     * A rule the caller may see, deleted or not (its audit trail stays readable): missing or not theirs to see is
     * 404. Live only.
     */
    @Transactional(readOnly = true)
    public AutomationRule get(Long id) {
        AutomationRule r = repository.findById(id).orElseThrow(RuleService::notFound);
        if (!visibility.canSee(r.getOwnerUserId(), r.getRegionIds())) throw notFound();
        return r;
    }

    /** A rule standing now; as of a date, its revision in force then (404 when it did not stand then). */
    @Transactional(readOnly = true)
    public RuleDtos.RuleDto detail(Long id) {
        AsOfPoint point = AsOf.current();
        if (point != null) {
            RuleDtos.RuleSnapshot s = asOfLists.revisionAt(id, point).orElseThrow(RuleService::notFound);
            if (!visibility.canSee(s.ownerUserId(), s.regionIds())) throw notFound();
            return toDto(s);
        }
        AutomationRule r = get(id);
        if (r.isDeleted()) throw notFound();
        return toDto(r);
    }

    /** The rule as the engine reads it: now, or as of the bound point from its revision then. */
    @Transactional(readOnly = true)
    public RuleDefinition definition(Long id) {
        AsOfPoint point = AsOf.current();
        if (point != null) {
            RuleDtos.RuleSnapshot s = asOfLists.revisionAt(id, point).orElseThrow(RuleService::notFound);
            if (!visibility.canSee(s.ownerUserId(), s.regionIds())) throw notFound();
            return definitionOf(s);
        }
        AutomationRule r = get(id);
        if (r.isDeleted()) throw notFound();
        return definitionOf(r);
    }

    // ---- writes ---------------------------------------------------------------------------------------------

    @Transactional
    public RuleDtos.RuleDto create(RuleDtos.RuleRequest req) {
        ViewScopeHolder.requireLive();
        User me = staff();
        RuleValidator.Validated v = validator.validate(req, null, me.getId());
        RuleDefinition d = v.definition();
        Instant now = clock.instant();
        AutomationRule r = AutomationRule.builder()
                .ownerUserId(me.getId())
                .createdByUserId(me.getId())
                .updatedByUserId(me.getId())
                .createdAt(now)
                .updatedAt(now)
                .regionIds(new HashSet<>())
                .build();
        apply(r, d);
        r.setEnabled(d.enabled());
        r.setActiveSince(d.enabled() ? now : null);
        schedule(r, now, false);
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        auditService.record(ENTITY, r.getId(), "RULE_CREATED", null, snapshotOf(r), me.getId(), null, null);
        return toDto(r, v.warnings());
    }

    /**
     * The owner changes their rule; anyone else needs AUTOMATION_ADMIN and says they take it over, and then owns it.
     * A stale version is a 409.
     */
    @Transactional
    public RuleDtos.RuleDto update(Long id, RuleDtos.RuleRequest req) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        requireVersion(r, req.version());
        boolean takesOver = takeOver(r, Boolean.TRUE.equals(req.confirmOwnershipChange()));
        RuleValidator.Validated v = validator.validate(keepingUnsent(req, r), id, me.getId());
        RuleDtos.RuleSnapshot before = snapshotOf(r);
        Long previousOwner = r.getOwnerUserId();
        boolean wasEnabled = r.isEnabled();
        ScheduleCalculator.Spec wasScheduled = ScheduleCalculator.Spec.of(r);
        apply(r, v.definition());
        r.setEnabled(v.definition().enabled());
        if (!wasEnabled && r.isEnabled()) r.setActiveSince(clock.instant());
        schedule(r, clock.instant(), wasEnabled && Objects.equals(wasScheduled, ScheduleCalculator.Spec.of(r)));
        if (takesOver) r.setOwnerUserId(me.getId());
        r.setUpdatedByUserId(me.getId());
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        auditService.record(ENTITY, id, "RULE_UPDATED", before, snapshotOf(r), me.getId(), null, null);
        if (takesOver) ownershipTaken(r, previousOwner, me);
        return toDto(r, v.warnings());
    }

    /** Turning a rule on makes it act, so it is done by its owner: an administrator takes it over first (SC-2). */
    @Transactional
    public RuleDtos.RuleDto enable(Long id, boolean confirmOwnershipChange) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        boolean takesOver = takeOver(r, confirmOwnershipChange);
        validator.requireRights(definitionOf(r));
        Long previousOwner = r.getOwnerUserId();
        boolean wasEnabled = r.isEnabled();
        if (!wasEnabled) {
            r.setEnabled(true);
            r.setActiveSince(clock.instant());
        }
        schedule(r, clock.instant(), wasEnabled);
        if (takesOver) r.setOwnerUserId(me.getId());
        r.setUpdatedByUserId(me.getId());
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        auditService.record(ENTITY, id, "RULE_ENABLED", null, snapshotOf(r), me.getId(), null, null);
        if (takesOver) ownershipTaken(r, previousOwner, me);
        return toDto(r);
    }

    /** Stopping a rule is its owner's or an administrator's, without taking it over. */
    @Transactional
    public RuleDtos.RuleDto disable(Long id) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        requireMayStop(r);
        r.setEnabled(false);
        r.setNextRunAt(null);
        r.setUpdatedByUserId(me.getId());
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        auditService.record(ENTITY, id, "RULE_DISABLED", null, snapshotOf(r), me.getId(), null, null);
        return toDto(r);
    }

    /** A soft delete: the rule stops, and its revisions, runs and audit trail stay. */
    @Transactional
    public void delete(Long id) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        requireMayStop(r);
        RuleDtos.RuleSnapshot before = snapshotOf(r);
        r.setDeletedAt(clock.instant());
        r.setEnabled(false);
        r.setNextRunAt(null);
        r.setUpdatedByUserId(me.getId());
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        auditService.record(ENTITY, id, "RULE_DELETED", before, null, me.getId(), null, null);
    }

    // ---- suspension and resuming (§5.10.7) -------------------------------------------------------------------

    /**
     * Stops the rule acting on its own, for the first reason only: a JPQL update, so a user's edit against the
     * rule's version still goes through. With it, in the same transaction, a revision, the audit row, and a notice
     * to the owner (if active) and every administrator of automation. False when it was suspended already.
     * {@code suspended_at} is the moment from which the rule's changes go unasked: now, or the earliest change of its
     * kind still waiting for the consumer, which it will now not be asked about either (A5: a resume with replay
     * goes through it too).
     */
    @Transactional
    public boolean suspend(Long id, SuspendReason reason, String detail) {
        Instant now = clock.instant();
        Instant since = repository.findById(id).map(r -> backlogSince(r, now)).orElse(now);
        if (repository.suspend(id, reason, Fit.to(detail, DETAIL_MAX), since) == 0) return false;
        AutomationRule r = repository.findById(id).orElseThrow();
        revise(r, null);
        auditService.record(ENTITY, id, "RULE_SUSPENDED", null, snapshotOf(r), null, null, detail);
        Set<Long> told = new LinkedHashSet<>();
        userRepository.findById(r.getOwnerUserId()).filter(User::isActive).ifPresent(u -> told.add(u.getId()));
        userRepository.findActiveStaffHolding(Privileges.AUTOMATION_ADMIN).forEach(u -> told.add(u.getId()));
        for (Long userId : told) {
            notificationService.notify(userId, NOTIF_SUSPENDED, Fit.to("Rule suspended: " + r.getName(), 200),
                    detail, "/automation/" + id);
        }
        return true;
    }

    /** The rules of someone who can no longer act (deactivated): each is suspended now, not at its next event. */
    @Transactional
    public int suspendOwnedBy(Long userId, SuspendReason reason) {
        User owner = userRepository.findById(userId).orElse(null);
        String detail = (owner == null ? "The rule's owner" : EmailText.nameOf(owner))
                + " is no longer active, so the rule cannot run as them";
        int n = 0;
        for (Long id : repository.findActiveIdsOwnedBy(userId)) {
            if (suspend(id, reason, detail)) n++;
        }
        return n;
    }

    /**
     * Whether this person's rules can still run as them (a role or grant changed): each that cannot is suspended
     * with the reason. The same check the consumer makes at every event.
     */
    @Transactional
    public int recheckOwner(Long userId) {
        int n = 0;
        for (Long id : repository.findActiveIdsOwnedBy(userId)) {
            AutomationRule r = repository.findById(id).orElse(null);
            if (r == null) continue;
            try {
                ownerSession.require(definitionOf(r));
            } catch (OwnerUnavailableException e) {
                if (suspend(id, e.reason(), e.getMessage())) n++;
            } catch (RuntimeException e) {
                // A rule that cannot be read is suspended by the consumer at its next event, with the reason.
            }
        }
        return n;
    }

    /** {@link #recheckOwner} for everyone holding a role whose privileges changed. */
    @Transactional
    public int recheckOwnersOfRole(Long roleId) {
        int n = 0;
        for (Long owner : repository.findOwnersWithRole(roleId)) n += recheckOwner(owner);
        return n;
    }

    /**
     * A suspended rule acts again. It is checked as a save checks it, as the one resuming it (who owns it, or takes it
     * over). Without {@code replay} it acts from now on: the changes made while it was suspended are counted but not
     * acted on. With it, a REPLAY event has the consumer go through those changes, each at its own moment (PC-18);
     * those already purged (older than {@code event-retention-days}) are gone.
     */
    @Transactional
    public RuleDtos.ResumedDto resume(Long id, boolean replay, boolean confirmOwnershipChange) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        if (!r.isSuspended()) throw new BadRequestException("The rule is not suspended");
        boolean takesOver = takeOver(r, confirmOwnershipChange);
        RuleDefinition d = validator.validate(requestOf(r), id, me.getId()).definition();
        try {
            ownerSession.require(d);
        } catch (OwnerUnavailableException e) {
            throw new BadRequestException(e.getMessage());
        }
        Instant now = clock.instant();
        long skipped = skippedWhileSuspended(r, now);
        Instant suspendedAt = r.getSuspendedAt();
        boolean replays = replay && suspendedAt != null && !r.getTriggerKind().isSchedule()
                && r.getTriggerKind() != TriggerKind.MANUAL;
        Long previousOwner = r.getOwnerUserId();
        r.setSuspendedReason(null);
        r.setSuspendedDetail(null);
        r.setSuspendedAt(null);
        if (!replays) {
            r.setActiveSince(now);
        } else if (r.getActiveSince() == null || r.getActiveSince().isAfter(suspendedAt)) {
            // The changes from the suspension on are the rule's again: those still waiting run live, the rest replay.
            r.setActiveSince(suspendedAt);
        }
        schedule(r, now, false);
        if (takesOver) r.setOwnerUserId(me.getId());
        r.setUpdatedByUserId(me.getId());
        r = repository.saveAndFlush(r);
        revise(r, me.getId());
        if (replays) {
            eventRepository.save(AutomationEvent.builder()
                    .kind(AutomationEvent.REPLAY)
                    .ruleId(id)
                    .ruleVersion(r.getVersion())
                    .origin(WriteOrigin.Kind.USER.name())
                    .depth(0)
                    .actorUserId(me.getId())
                    .scheduledFor(suspendedAt)
                    .occurredAt(now)
                    .status(AutomationEvent.PENDING)
                    .build());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("replay", replays);
        after.put("skippedWhileSuspended", skipped);
        auditService.record(ENTITY, id, "RULE_RESUMED", null, after, me.getId(), null, null);
        if (takesOver) ownershipTaken(r, previousOwner, me);
        return new RuleDtos.ResumedDto(toDto(r), skipped);
    }

    /** Changes to the rule's kind of record, of the kinds it fires on, made while it was suspended (PC-18). */
    private long skippedWhileSuspended(AutomationRule r, Instant now) {
        List<String> kinds = changeKinds(r.getTriggerKind());
        if (r.getSuspendedAt() == null || kinds.isEmpty()) return 0;
        return eventRepository.countEntityEvents(r.getEntityType().name(), kinds,
                asksAboutChildren(r.getTriggerKind(), r.getEntityType()), r.getSuspendedAt(), now);
    }

    /** The earliest change the rule would be asked about still waiting for the consumer, if before {@code now}. */
    private Instant backlogSince(AutomationRule r, Instant now) {
        List<String> kinds = changeKinds(r.getTriggerKind());
        if (kinds.isEmpty() || r.isSuspended()) return now;
        Instant earliest = eventRepository.earliestUnprocessed(r.getEntityType().name(), kinds,
                asksAboutChildren(r.getTriggerKind(), r.getEntityType()));
        return earliest != null && earliest.isBefore(now) ? earliest : now;
    }

    /** The kinds of change a rule is asked about (§5.10.6): none for a schedule or a rule run by hand. */
    public static List<String> changeKinds(TriggerKind trigger) {
        if (trigger.isSchedule() || trigger == TriggerKind.MANUAL) return List.of();
        List<String> kinds = new ArrayList<>();
        if (trigger != TriggerKind.ENTITY_UPDATED) kinds.add(AutomationEvent.CREATED);
        if (trigger.firesOnUpdate()) kinds.add(AutomationEvent.UPDATED);
        return kinds;
    }

    /** Whether an invoice or payment rule is also asked about its customer's changes (CHILDREN). */
    public static boolean asksAboutChildren(TriggerKind trigger, TriggerEntity entity) {
        return trigger.firesOnUpdate() && entity != TriggerEntity.CUSTOMER;
    }

    // ---- running by hand (§5.10.9) ----------------------------------------------------------------------------

    /** The run asked for, and whether it is new (202) or an identical one already waiting (200). */
    public record RunQueued(RuleDtos.QueuedRunDto run, boolean created) {}

    /**
     * Runs the rule now, or at {@code runAt}, over what it matches or the records named, in the regions asked about.
     * The rule is locked first, so two identical requests make one run. It may be off (a rule is run by hand on
     * purpose, PC-6) but not suspended; whoever runs it is its owner, an administrator taking it over first (SC-2).
     */
    @Transactional
    public RunQueued runNow(Long id, RuleDtos.RunRequest req) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRule r = lockStanding(id);
        boolean takesOver = takeOver(r, req != null && Boolean.TRUE.equals(req.confirmOwnershipChange()));
        if (r.isSuspended()) throw new BadRequestException("The rule is suspended; resume it first");
        Instant now = clock.instant();
        Map<String, String> errors = new LinkedHashMap<>();
        String recordIds = recordIds(req == null ? null : req.recordIds(), errors);
        Instant runAt = req == null ? null : req.runAt();
        if (runAt != null && (!runAt.isAfter(now) || runAt.isAfter(now.plus(Duration.ofDays(MAX_RUN_AHEAD_DAYS))))) {
            errors.put("runAt", "must be later than now and at most " + MAX_RUN_AHEAD_DAYS + " days ahead");
        }
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);
        validator.requireRights(definitionOf(r));
        Set<Long> asked = ViewScopeHolder.current().regionIds();
        String regionIds = asked == null ? null
                : asked.stream().map(String::valueOf).collect(Collectors.joining(","));

        for (AutomationEvent queued : eventRepository.findQueuedManual(id)) {
            boolean same = Objects.equals(queued.getRecordIds(), recordIds)
                    && Objects.equals(queued.getRegionIds(), regionIds)
                    && (runAt == null ? queued.getNextAttemptAt() == null || !queued.getScheduledFor().isAfter(now)
                    : runAt.equals(queued.getScheduledFor()));
            if (!same) continue;
            AutomationRun run = runRepository.findByRuleIdAndEventId(id, queued.getId()).orElse(null);
            if (run == null) continue;
            return new RunQueued(new RuleDtos.QueuedRunDto(run.getId(), queued.getId(), run.getStatus().name(),
                    run.getScheduledFor()), false);
        }

        Long previousOwner = r.getOwnerUserId();
        if (takesOver) {
            r.setOwnerUserId(me.getId());
            r.setUpdatedByUserId(me.getId());
            r = repository.saveAndFlush(r);
            revise(r, me.getId());
            ownershipTaken(r, previousOwner, me);
        }
        AutomationEvent ev = eventRepository.save(AutomationEvent.builder()
                .kind(AutomationEvent.MANUAL)
                .ruleId(id)
                .ruleVersion(r.getVersion())
                .recordIds(recordIds)
                .regionIds(regionIds)
                .origin(WriteOrigin.Kind.USER.name())
                .depth(0)
                .actorUserId(me.getId())
                .scheduledFor(runAt == null ? now : runAt)
                .occurredAt(now)
                .nextAttemptAt(runAt)
                .status(AutomationEvent.PENDING)
                .build());
        AutomationRun run = runRepository.save(AutomationRun.of(id, r.getVersion(), ev, RunStatus.QUEUED, me.getId(),
                now));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("runId", run.getId());
        request.put("recordIds", recordIds);
        request.put("regionIds", regionIds);
        request.put("runAt", runAt);
        auditService.record(ENTITY, id, "RULE_RUN_REQUESTED", null, request, me.getId(), null, null);
        return new RunQueued(new RuleDtos.QueuedRunDto(run.getId(), ev.getId(), run.getStatus().name(),
                run.getScheduledFor()), true);
    }

    /** At most 50 positive ids, each once, kept exactly as given (never cut, PC-12); null for none. */
    private static String recordIds(List<Long> ids, Map<String, String> errors) {
        if (ids == null || ids.isEmpty()) return null;
        if (ids.size() > MAX_RUN_RECORDS) {
            errors.put("recordIds", "At most " + MAX_RUN_RECORDS + " records can be run at once");
            return null;
        }
        if (ids.stream().anyMatch(i -> i == null || i <= 0)) {
            errors.put("recordIds", "must be record ids");
            return null;
        }
        return new LinkedHashSet<>(ids).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * A run whose event was given up on, tried again: the event goes back in the queue as new and its failed runs
     * wait again. It redelivers something that happened, so the owner or an administrator may, except for a run by
     * hand, which makes the rule act and so is its owner's (an administrator takes it over first).
     */
    @Transactional
    public RuleDtos.QueuedRunDto retry(Long runId, boolean confirmOwnershipChange) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
        AutomationRule r = lockStanding(run.getRuleId());
        AutomationEvent ev = eventRepository.findById(run.getEventId()).orElse(null);
        boolean manual = ev != null && AutomationEvent.MANUAL.equals(ev.getKind());
        if (manual) {
            boolean takesOver = takeOver(r, confirmOwnershipChange);
            if (takesOver) {
                validator.requireRights(definitionOf(r));
                Long previousOwner = r.getOwnerUserId();
                r.setOwnerUserId(me.getId());
                r.setUpdatedByUserId(me.getId());
                r = repository.saveAndFlush(r);
                revise(r, me.getId());
                ownershipTaken(r, previousOwner, me);
            }
        } else {
            requireMayStop(r);
        }
        if (run.getStatus() != RunStatus.FAILED || ev == null || !AutomationEvent.DEAD.equals(ev.getStatus())) {
            throw new BadRequestException("Only a failed run whose event was given up on can be tried again");
        }
        requeueEvent(ev.getId());
        auditService.record(ENTITY, r.getId(), "RULE_RUN_REQUESTED", null,
                Map.of("runId", run.getId(), "retry", true), me.getId(), null, null);
        return new RuleDtos.QueuedRunDto(run.getId(), ev.getId(), RunStatus.QUEUED.name(), run.getScheduledFor());
    }

    /**
     * An event given up on, back in the queue (an administrator's call, §6.14). A run by hand makes the rule act, even
     * a disabled one, and so is its owner's (SC-3): anyone else tries it again from its run, which takes the rule over.
     */
    @Transactional
    public Map<String, Object> requeue(Long eventId) {
        ViewScopeHolder.requireLive();
        User me = staff();
        AutomationEvent ev = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (AutomationEvent.MANUAL.equals(ev.getKind())) {
            AutomationRule r = ev.getRuleId() == null ? null : repository.findById(ev.getRuleId()).orElse(null);
            if (r == null || !me.getId().equals(r.getOwnerUserId())) {
                Long runId = runRepository.findByEventIdOrderByIdAsc(eventId).stream().findFirst()
                        .map(AutomationRun::getId).orElse(null);
                throw new BadRequestException("A run by hand is tried again from its run: open the rule's Runs tab "
                        + "and use Retry, which takes the rule over" + (runId == null ? "" : " (run #" + runId + ")"));
            }
        }
        if (!AutomationEvent.DEAD.equals(ev.getStatus())) {
            throw new BadRequestException("Only an event that was given up on can be queued again");
        }
        requeueEvent(eventId);
        return Map.of("eventId", eventId, "status", AutomationEvent.PENDING);
    }

    private void requeueEvent(Long eventId) {
        Instant now = clock.instant();
        eventRepository.requeue(eventId, now);
        for (AutomationRun failed : runRepository.findByEventIdOrderByIdAsc(eventId)) {
            if (failed.getStatus() != RunStatus.FAILED) continue;
            failed.setStatus(RunStatus.QUEUED);
            failed.setFinishedAt(null);
            failed.setMessage(null);
            runRepository.save(failed);
        }
    }

    /** The stored rule as its editor would send it: what a resume checks again, as a save would. */
    private RuleDtos.RuleRequest requestOf(AutomationRule r) {
        JsonNode actions = tree(r.getActionsJson());
        List<JsonNode> list = new ArrayList<>();
        if (actions != null) actions.forEach(list::add);
        return new RuleDtos.RuleRequest(r.getName(), r.getDescription(), r.isEnabled(), triggerOf(r),
                List.copyOf(new TreeSet<>(r.getRegionIds())), tree(r.getConditionJson()), list,
                r.getMaxRecordsPerRun(), r.getVersion(), null);
    }

    private User staff() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        return me;
    }

    /** The rule locked for a change, standing and visible to the caller; anything else is 404. */
    private AutomationRule lockStanding(Long id) {
        AutomationRule r = repository.findByIdForUpdate(id).orElseThrow(RuleService::notFound);
        if (r.isDeleted() || !visibility.canSee(r.getOwnerUserId(), r.getRegionIds())) throw notFound();
        return r;
    }

    /**
     * The request with what it leaves out kept as the rule has it: a PUT without {@code enabled} does not turn a
     * disabled rule on, nor one without {@code maxRecordsPerRun} reset its limit.
     */
    private static RuleDtos.RuleRequest keepingUnsent(RuleDtos.RuleRequest req, AutomationRule r) {
        if (req == null) return null;
        return new RuleDtos.RuleRequest(req.name(), req.description(),
                req.enabled() == null ? r.isEnabled() : req.enabled(), req.trigger(), req.regionIds(),
                req.condition(), req.actions(),
                req.maxRecordsPerRun() == null ? r.getMaxRecordsPerRun() : req.maxRecordsPerRun(),
                req.version(), req.confirmOwnershipChange());
    }

    private static void requireVersion(AutomationRule r, Long version) {
        if (version != null && !version.equals(r.getVersion())) {
            throw new OptimisticLockingFailureException("Rule " + r.getId() + " was changed by someone else");
        }
    }

    /**
     * Whether acting on the rule makes the caller its owner: never for the owner (who must hold AUTOMATION_MANAGE);
     * for anyone else only with AUTOMATION_ADMIN (403 otherwise) and a confirmation (400 otherwise).
     */
    private boolean takeOver(AutomationRule r, boolean confirmed) {
        if (r.getOwnerUserId().equals(currentUser.idOrNull())) {
            if (!currentUser.has(Privileges.AUTOMATION_MANAGE)) throw new AccessDeniedException("Not allowed");
            return false;
        }
        if (!currentUser.has(Privileges.AUTOMATION_ADMIN)) throw new AccessDeniedException("Not allowed");
        if (!confirmed) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("confirmOwnershipChange", TAKE_OVER));
        }
        return true;
    }

    private void requireMayStop(AutomationRule r) {
        boolean owner = r.getOwnerUserId().equals(currentUser.idOrNull())
                && currentUser.has(Privileges.AUTOMATION_MANAGE);
        if (!owner && !currentUser.has(Privileges.AUTOMATION_ADMIN)) throw new AccessDeniedException("Not allowed");
    }

    private void ownershipTaken(AutomationRule r, Long previousOwner, User me) {
        auditService.record(ENTITY, r.getId(), "RULE_OWNERSHIP_TAKEN", Map.of("ownerUserId", previousOwner),
                Map.of("ownerUserId", me.getId()), me.getId(), null, null);
    }

    private void apply(AutomationRule r, RuleDefinition d) {
        r.setName(d.name());
        r.setDescription(d.description());
        r.setTriggerKind(d.triggerKind());
        r.setEntityType(d.entity());
        r.setScheduleTime(d.scheduleTime());
        r.setScheduleDay(d.scheduleDay());
        r.setScheduleZone(d.zone().getId());
        r.setCatchUp(d.catchUp());
        r.setConditionJson(d.condition() == null ? null
                : ConditionJson.write(d.condition(), objectMapper).toString());
        r.setActionsJson(RuleActions.write(d.actions(), objectMapper));
        r.setMaxRecordsPerRun(d.maxRecordsPerRun());
        if (!r.getRegionIds().equals(d.regionIds())) {
            r.getRegionIds().clear();
            r.getRegionIds().addAll(d.regionIds());
        }
    }

    /** A revision in the same transaction as the change (D8). */
    private void revise(AutomationRule r, Long savedBy) {
        RuleDtos.RuleSnapshot s = snapshotOf(r);
        revisionRepository.save(AutomationRuleRevision.builder()
                .ruleId(r.getId())
                .ruleVersion(r.getVersion())
                .snapshotJson(json(s))
                .name(r.getName())
                .entityType(r.getEntityType().name())
                .triggerKind(r.getTriggerKind().name())
                .ownerUserId(r.getOwnerUserId())
                .enabled(r.isEnabled())
                .suspendedReason(name(r.getSuspendedReason()))
                .deleted(r.isDeleted())
                .savedByUserId(savedBy)
                .savedAt(clock.instant())
                .build());
    }

    /**
     * When a scheduled rule next runs (§5.10.8): the first slot after {@code now} while it is on and standing, none
     * otherwise. {@code keep} leaves a slot already set alone (the schedule did not change), so a slot still due is
     * not skipped by an edit.
     */
    private static void schedule(AutomationRule r, Instant now, boolean keep) {
        ScheduleCalculator.Spec spec = ScheduleCalculator.Spec.of(r);
        if (spec == null || !r.isEnabled() || r.isSuspended() || r.isDeleted()) {
            r.setNextRunAt(null);
            return;
        }
        if (keep && r.getNextRunAt() != null) return;
        r.setNextRunAt(ScheduleCalculator.nextAfter(spec, now));
    }

    // ---- views ----------------------------------------------------------------------------------------------

    /** The rules a set answers: those the caller sees, in the regions asked about, not deleted. */
    private ScopeResolver.Scope scope() {
        PredicateFactory seen = visibility.predicate();
        List<PredicateFactory> predicates = List.of((root, q, cb) -> cb.isNull(root.get("deletedAt")), seen);
        return new ScopeResolver.Scope(predicates, List.of(),
                new ScopeResolver.RegionCoverage(visibility.answered(), Set.of(), false));
    }

    public RuleDefinition definitionOf(AutomationRule r) {
        return new RuleDefinition(r.getId(), r.getName(), r.getDescription(), r.isEnabled(), r.getTriggerKind(),
                r.getEntityType(), r.getScheduleTime(), r.getScheduleDay(), ZoneId.of(r.getScheduleZone()),
                r.getCatchUp(), new TreeSet<>(r.getRegionIds()),
                ConditionJson.read(r.getConditionJson(), objectMapper),
                RuleActions.read(r.getActionsJson(), objectMapper), r.getMaxRecordsPerRun(), r.getOwnerUserId());
    }

    public RuleDefinition definitionOf(RuleDtos.RuleSnapshot s) {
        RuleDtos.TriggerDto t = s.trigger();
        return new RuleDefinition(s.id(), s.name(), s.description(), s.enabled(), TriggerKind.valueOf(t.kind()),
                TriggerEntity.valueOf(t.entityType()), t.time(), t.day(), ZoneId.of(t.zone()), t.catchUp(),
                new TreeSet<>(s.regionIds()),
                s.condition() == null || s.condition().isNull() ? null
                        : ConditionJson.read(s.condition().toString(), objectMapper),
                RuleActions.read(s.actions(), objectMapper), s.maxRecordsPerRun(), s.ownerUserId());
    }

    public RuleDtos.RuleSnapshot snapshotOf(AutomationRule r) {
        return new RuleDtos.RuleSnapshot(r.getId(), r.getName(), r.getDescription(), r.isEnabled(), r.getVersion(),
                name(r.getSuspendedReason()), r.getSuspendedDetail(), r.getOwnerUserId(), triggerOf(r),
                List.copyOf(new TreeSet<>(r.getRegionIds())), tree(r.getConditionJson()), tree(r.getActionsJson()),
                r.getMaxRecordsPerRun(), r.isDeleted(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static RuleDtos.TriggerDto triggerOf(AutomationRule r) {
        return new RuleDtos.TriggerDto(r.getTriggerKind().name(), r.getEntityType().name(), r.getScheduleTime(),
                r.getScheduleDay(), r.getScheduleZone(), r.getTriggerKind().isSchedule() ? r.getCatchUp() : null);
    }

    public RuleDtos.RuleDto toDto(AutomationRule r) {
        return toDto(r, validator.warnings(definitionOf(r)));
    }

    private RuleDtos.RuleDto toDto(AutomationRule r, List<String> warnings) {
        RuleDefinition d = definitionOf(r);
        User owner = userRepository.findById(r.getOwnerUserId()).orElse(null);
        boolean live = !AsOf.active();
        boolean mine = r.getOwnerUserId().equals(currentUser.idOrNull());
        boolean manage = currentUser.has(Privileges.AUTOMATION_MANAGE);
        List<String> all = new ArrayList<>(warnings);
        all.addAll(emailWarnings(d, owner));
        return new RuleDtos.RuleDto(r.getId(), r.getName(), r.getDescription(), r.isEnabled(), r.getVersion(),
                status(r.isEnabled(), name(r.getSuspendedReason())), name(r.getSuspendedReason()),
                r.getSuspendedDetail(), ownerDto(owner, r.getOwnerUserId()), triggerOf(r),
                List.copyOf(new TreeSet<>(r.getRegionIds())), tree(r.getConditionJson()), tree(r.getActionsJson()),
                r.getMaxRecordsPerRun(), r.getNextRunAt(), lastRun(r), all, listLink(d),
                live && mine && manage && !r.isDeleted(),
                live && !mine && currentUser.has(Privileges.AUTOMATION_ADMIN) && !r.isDeleted(),
                live && mine && manage && !r.isDeleted(), r.getCreatedAt(), r.getUpdatedAt(),
                people(tree(r.getActionsJson())));
    }

    /** A rule as it stood at a point: its revision, with nothing a user could do to it then. */
    private RuleDtos.RuleDto toDto(RuleDtos.RuleSnapshot s) {
        RuleDefinition d = definitionOf(s);
        User owner = userRepository.findById(s.ownerUserId()).orElse(null);
        return new RuleDtos.RuleDto(s.id(), s.name(), s.description(), s.enabled(), s.version(),
                status(s.enabled(), s.suspendedReason()), s.suspendedReason(), s.suspendedDetail(),
                ownerDto(owner, s.ownerUserId()), s.trigger(), s.regionIds(), s.condition(), s.actions(),
                s.maxRecordsPerRun(), null, null, validator.warnings(d), listLink(d), false, false, false,
                s.createdAt(), s.updatedAt(), people(s.actions()));
    }

    /**
     * The names of the people the actions name, by id: every {@code USER} token anywhere in them (assignees, the
     * collection POC, from, to, cc), read at once. Someone no longer there is left out.
     */
    private Map<Long, String> people(JsonNode actions) {
        Set<Long> ids = new TreeSet<>();
        collectUsers(actions, ids);
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new TreeMap<>();
        for (User u : userRepository.findAllById(ids)) names.put(u.getId(), EmailText.nameOf(u));
        return names;
    }

    private static void collectUsers(JsonNode node, Set<Long> ids) {
        if (node == null) return;
        if (node.isObject() && "USER".equals(node.path("type").asText(null))
                && node.path("userId").isIntegralNumber() && node.path("userId").canConvertToLong()) {
            ids.add(node.get("userId").asLong());
        }
        if (node.isContainerNode()) node.forEach(child -> collectUsers(child, ids));
    }

    private RuleDtos.RuleSummary summary(AutomationRule r, Map<Long, User> owners) {
        return new RuleDtos.RuleSummary(r.getId(), r.getName(), r.isEnabled(),
                status(r.isEnabled(), name(r.getSuspendedReason())), name(r.getSuspendedReason()),
                r.getTriggerKind().name(), r.getEntityType().name(),
                ownerDto(owners.get(r.getOwnerUserId()), r.getOwnerUserId()),
                List.copyOf(new TreeSet<>(r.getRegionIds())), name(r.getLastRunStatus()), r.getLastRunAt(),
                r.getNextRunAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private RuleDtos.RuleSummary summary(RuleDtos.RuleSnapshot s, Map<Long, User> owners) {
        return new RuleDtos.RuleSummary(s.id(), s.name(), s.enabled(), status(s.enabled(), s.suspendedReason()),
                s.suspendedReason(), s.trigger().kind(), s.trigger().entityType(),
                ownerDto(owners.get(s.ownerUserId()), s.ownerUserId()), s.regionIds(), null, null, null,
                s.createdAt(), s.updatedAt());
    }

    private RuleDtos.LastRunDto lastRun(AutomationRule r) {
        if (r.getLastRunId() == null) return null;
        AutomationRun run = runRepository.findById(r.getLastRunId()).orElse(null);
        if (run == null) {
            return new RuleDtos.LastRunDto(r.getLastRunId(), name(r.getLastRunStatus()), r.getLastRunAt(),
                    null, null, null, null);
        }
        return new RuleDtos.LastRunDto(run.getId(), run.getStatus().name(), run.getFinishedAt(), run.getMatched(),
                run.getDone(), run.getSkipped(), run.getFailed());
    }

    /** Emails from the owner would be kept but not sent: delivery is off, or the owner has not connected Gmail. */
    private List<String> emailWarnings(RuleDefinition d, User owner) {
        boolean fromOwner = d.actions().stream().anyMatch(a -> a instanceof SendEmailAction e
                && (e.from() == null || e.fallbackToOwner()));
        if (!fromOwner || owner == null) return List.of();
        if (!transport.isConfigured()) {
            return List.of("Email delivery is not configured, so emails from this rule will be saved in the app "
                    + "but not sent.");
        }
        GmailStatus gmail = gmailConnections.statuses(List.of(owner.getId()))
                .getOrDefault(owner.getId(), GmailStatus.NOT_CONNECTED);
        return gmail == GmailStatus.CONNECTED ? List.of()
                : List.of(EmailText.nameOf(owner) + " has not connected Gmail, so emails sent from them will be "
                + "saved in the app but not sent.");
    }

    /** The list page showing what an AND-only condition matches: its filters, in order. */
    static String listLink(RuleDefinition d) {
        if (d.condition() != null && !d.condition().andOnly()) return null;
        List<String> filters = ConditionJson.filters(d.condition());
        if (filters.isEmpty()) return d.entity().listPath();
        return d.entity().listPath() + "?" + filters.stream()
                .map(f -> "f=" + URLEncoder.encode(f, StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    static String status(boolean enabled, String suspendedReason) {
        if (suspendedReason != null) return "SUSPENDED";
        return enabled ? "ACTIVE" : "DISABLED";
    }

    private static RuleDtos.OwnerDto ownerDto(User owner, Long ownerId) {
        return owner == null ? new RuleDtos.OwnerDto(ownerId, "User #" + ownerId, false)
                : new RuleDtos.OwnerDto(owner.getId(), EmailText.nameOf(owner), owner.isActive());
    }

    private Map<Long, User> owners(Collection<Long> ids) {
        return userRepository.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private JsonNode tree(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored rule JSON is not readable", e);
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("Rule not found");
    }
}
