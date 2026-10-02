package com.geneinvoice.automation.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.action.RecordContexts;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.event.AutomationEventRepository;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.RuleService;
import com.geneinvoice.automation.rule.RuleVisibility;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.EmailText;
import com.geneinvoice.history.Records;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs and effects as a viewer may see them (§5.10.10; PC-8, SC-13). The rule's owner and administrators of
 * automation see everything. Anyone else who may see the rule sees its runs with the counters counted only over
 * the records they can see ({@code partial}), and an effect or a run's record they cannot see only as its kind:
 * messages and warnings name records and people, so they go too. As of a date, a run is there once it was created,
 * and says where it stood then ({@code statusAt}).
 */
@Component
@RequiredArgsConstructor
public class RunViews {

    /** The page parameters of a run's effects: in the order they were made. */
    static final TableSchema EFFECTS = TableSchema.of("automation-effects", "id,asc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).notFilterable().build());

    /** Columns a partial viewer sees recounted, or not at all: they cannot filter or sort on them. */
    static final Set<String> REDACTED_COLUMNS = Set.of("entityId", "matched", "done", "skipped", "failed");

    private static final Instant EVER = Instant.parse("9999-12-31T00:00:00Z");
    private static final int VISIBLE_CHUNK = 1000;
    /** How many events given up on the health view lists, newest first. */
    static final int DEAD_LISTED = 50;

    private final RuleService ruleService;
    private final RuleVisibility visibility;
    private final AutomationRunRepository runRepository;
    private final AutomationEffectRepository effectRepository;
    private final TableQueryExecutor queryExecutor;
    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;
    private final Records records;
    private final UserRepository userRepository;
    private final AutomationEventRepository eventRepository;
    private final AutomationProperties props;
    private final Clock clock;

    /**
     * The consumer at a glance (§6.14, administrators only): whether it is on, the events waiting for their first try
     * ({@code pending}), being processed, waiting to be tried again ({@code retrying}) and given up on
     * ({@code dead}), how long the event due longest has waited, and the events given up on, with their errors.
     */
    public record HealthDto(boolean consumerEnabled, long pending, long processing, long retrying, long dead,
                            long oldestPendingSeconds, List<DeadEventDto> deadEvents) {}

    public record DeadEventDto(Long id, String kind, String entityType, Long entityId, Long ruleId, int attempts,
                               String lastError, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public HealthDto health() {
        Instant now = clock.instant();
        Instant oldest = eventRepository.oldestDue(now);
        List<DeadEventDto> dead = eventRepository.findByStatusOrderByUpdatedAtDescIdDesc(AutomationEvent.DEAD,
                        PageRequest.of(0, DEAD_LISTED)).stream()
                .map(e -> new DeadEventDto(e.getId(), e.getKind(), e.getEntityType(), e.getEntityId(), e.getRuleId(),
                        e.getAttempts(), e.getLastError(), e.getUpdatedAt()))
                .toList();
        return new HealthDto(props.isConsumerEnabled(),
                eventRepository.countFirstTries(AutomationEvent.PENDING),
                eventRepository.countByStatus(AutomationEvent.PROCESSING),
                eventRepository.countRetries(AutomationEvent.PENDING),
                eventRepository.countByStatus(AutomationEvent.DEAD),
                oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds()), dead);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetDto(String type, Long id, String label, String link, boolean hidden) {
        static TargetDto hiddenOne(String type) {
            return new TargetDto(type, null, null, null, true);
        }
    }

    public record PersonDto(Long id, String name) {}

    public record RunDto(Long id, Long ruleId, Long ruleVersion, Long eventId, String triggerType, String status,
                         String statusAt, Instant scheduledFor, Long lateBySeconds, TargetDto entity,
                         PersonDto requestedBy, int matched, int done, int skipped, int failed, boolean partial,
                         boolean truncated, String message, Instant createdAt, Instant startedAt,
                         Instant finishedAt) {}

    public record ResultDto(String type, Long id, String link) {}

    public record EffectDto(Long id, String actionKey, String actionType, TargetDto target, String status,
                            ResultDto result, String message, List<String> warnings, Instant createdAt) {}

    /**
     * A rule's runs, newest first by default; as of a date, those made by then. 404 for a rule not visible. Someone
     * who sees the runs only in part cannot filter or sort on what is stored unredacted (the record, the counters),
     * or the page's total would tell what the rows hide: 400.
     */
    @Transactional(readOnly = true)
    public PageResponse<RunDto> runs(Long ruleId, TableQuery query) {
        AutomationRule rule = ruleService.get(ruleId);
        boolean full = full(rule);
        if (!full) {
            if (REDACTED_COLUMNS.contains(query.sortField())) {
                throw new BadRequestException("Runs cannot be sorted by " + query.sortField()
                        + ": it counts records you cannot see");
            }
            for (FilterSpec spec : query.filters()) {
                if (REDACTED_COLUMNS.contains(spec.field())) {
                    throw new BadRequestException("Runs cannot be filtered by " + spec.field()
                            + ": it counts records you cannot see");
                }
            }
        }
        AsOfPoint point = AsOf.current();
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add((root, q, cb) -> cb.equal(root.get("ruleId"), ruleId));
        if (point != null) scope.add((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), point.instant()));
        var result = queryExecutor.run(AutomationRun.class, TableSchemas.AUTOMATION_RUNS, query, scope, List.of());
        List<RunDto> content = new ArrayList<>();
        for (AutomationRun run : result.content()) content.add(dto(run, full, point));
        return PageResponse.of(content, query, result.total(), List.of());
    }

    /** One run; 404 when its rule is not visible, or as of a date before it was made. */
    @Transactional(readOnly = true)
    public RunDto run(Long runId) {
        AutomationRun run = visibleRun(runId);
        return dto(run, full(ruleService.get(run.getRuleId())), AsOf.current());
    }

    /** A page of a run's effects, those on records the viewer cannot see told only by kind. */
    @Transactional(readOnly = true)
    public PageResponse<EffectDto> effects(Long runId, Integer page, Integer size) {
        AutomationRun run = visibleRun(runId);
        boolean full = full(ruleService.get(run.getRuleId()));
        AsOfPoint point = AsOf.current();
        TableQuery query = TableQuery.parse(EFFECTS, page, size, null, List.of());
        Page<AutomationEffect> found = effectRepository.findByRunId(runId, point == null ? EVER : point.instant(),
                PageRequest.of(query.page(), query.size(), Sort.by("id")));
        Map<String, Set<Long>> seen = full ? Map.of() : visibleTargets(found.getContent());
        List<EffectDto> content = found.getContent().stream()
                .map(e -> full || seen.getOrDefault(e.getTargetType(), Set.of()).contains(e.getTargetId())
                        ? effect(e) : hiddenEffect(e))
                .toList();
        return PageResponse.of(content, query, found.getTotalElements(), List.of());
    }

    private AutomationRun visibleRun(Long runId) {
        AutomationRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
        ruleService.get(run.getRuleId());
        AsOfPoint point = AsOf.current();
        if (point != null && run.getCreatedAt().isAfter(point.instant())) throw new NotFoundException("Run not found");
        return run;
    }

    /** The owner and administrators of automation see every run and effect in full. */
    private boolean full(AutomationRule rule) {
        return rule.getOwnerUserId().equals(currentUser.idOrNull()) || visibility.isAdmin();
    }

    private RunDto dto(AutomationRun run, boolean full, AsOfPoint point) {
        TargetDto entity = null;
        boolean entityHidden = false;
        if (run.getEntityType() != null && run.getEntityId() != null) {
            TriggerEntity type = TriggerEntity.valueOf(run.getEntityType());
            boolean seen = full || visible(type, List.of(run.getEntityId())).contains(run.getEntityId());
            entity = seen ? target(type, run.getEntityId(), null) : TargetDto.hiddenOne(type.name());
            entityHidden = !seen;
        }
        int matched = run.getMatched();
        int done = run.getDone();
        int skipped = run.getSkipped();
        int failed = run.getFailed();
        if (!full) {
            int[] counts = viewersCounters(run.getId());
            matched = counts[0];
            done = counts[1];
            skipped = counts[2];
            failed = counts[3];
        }
        User requester = run.getRequestedByUserId() == null ? null
                : userRepository.findById(run.getRequestedByUserId()).orElse(null);
        return new RunDto(run.getId(), run.getRuleId(), run.getRuleVersion(), run.getEventId(), run.getTriggerType(),
                run.getStatus().name(), point == null ? null : statusAt(run, point), run.getScheduledFor(),
                lateBy(run), entity,
                run.getRequestedByUserId() == null ? null : new PersonDto(run.getRequestedByUserId(),
                        requester == null ? "User #" + run.getRequestedByUserId() : EmailText.nameOf(requester)),
                matched, done, skipped, failed, !full, run.isTruncated(),
                entityHidden ? null : run.getMessage(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }

    /** Where a run stood at the point: waiting until it started, running until it finished, then as it ended. */
    static String statusAt(AutomationRun run, AsOfPoint point) {
        Instant p = point.instant();
        if (run.getStartedAt() == null || run.getStartedAt().isAfter(p)) return RunStatus.QUEUED.name();
        if (run.getFinishedAt() == null || run.getFinishedAt().isAfter(p)) return RunStatus.RUNNING.name();
        return run.getStatus().name();
    }

    private static Long lateBy(AutomationRun run) {
        if (run.getScheduledFor() == null || run.getStartedAt() == null) return null;
        return Math.max(0, Duration.between(run.getScheduledFor(), run.getStartedAt()).getSeconds());
    }

    /**
     * The run's matched, done, skipped and failed, counted only over the records the viewer can see: matched is the
     * visible records the run acted on, so how many it matched elsewhere is not told.
     */
    private int[] viewersCounters(Long runId) {
        List<Object[]> rows = effectRepository.targetsOf(runId);
        Map<String, List<Long>> byType = new HashMap<>();
        for (Object[] row : rows) byType.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((Long) row[1]);
        Map<String, Set<Long>> seen = new HashMap<>();
        byType.forEach((type, ids) -> seen.put(type, visible(TriggerEntity.valueOf(type), ids)));
        int[] counts = new int[4];
        Set<String> matched = new HashSet<>();
        for (Object[] row : rows) {
            if (!seen.getOrDefault((String) row[0], Set.of()).contains((Long) row[1])) continue;
            matched.add(row[0] + ":" + row[1]);
            switch ((EffectStatus) row[2]) {
                case DONE -> counts[1]++;
                case SKIPPED -> counts[2]++;
                case FAILED -> counts[3]++;
            }
        }
        counts[0] = matched.size();
        return counts;
    }

    private Map<String, Set<Long>> visibleTargets(List<AutomationEffect> effects) {
        Map<String, List<Long>> byType = new HashMap<>();
        effects.forEach(e -> byType.computeIfAbsent(e.getTargetType(), k -> new ArrayList<>()).add(e.getTargetId()));
        Map<String, Set<Long>> seen = new HashMap<>();
        byType.forEach((type, ids) -> seen.put(type, visible(TriggerEntity.valueOf(type), ids)));
        return seen;
    }

    /** Which of these records the viewer can see (their regions and book), at the point bound. */
    private Set<Long> visible(TriggerEntity type, List<Long> ids) {
        Set<Long> out = new HashSet<>();
        List<Long> distinct = List.copyOf(new HashSet<>(ids));
        List<PredicateFactory> base = scopeResolver.scope(type.kind(), ScopeResolver.Access.VIEW,
                ScopeResolver.Reach.GRANTED).predicates();
        for (int from = 0; from < distinct.size(); from += VISIBLE_CHUNK) {
            List<Long> chunk = distinct.subList(from, Math.min(distinct.size(), from + VISIBLE_CHUNK));
            List<PredicateFactory> scope = new ArrayList<>(base);
            scope.add((root, q, cb) -> root.get("id").in(chunk));
            out.addAll(queryExecutor.ids(type.type(), type.schema(),
                    new TableQuery(0, chunk.size(), "id", true, List.of()), scope, chunk.size()));
        }
        return out;
    }

    private TargetDto target(TriggerEntity type, Long id, String knownLabel) {
        String label = knownLabel;
        if (label == null) {
            label = records.find(type.type(), id).map(e -> RecordContexts.label(type, e))
                    .orElse(type.name().charAt(0) + type.noun().substring(1) + " #" + id);
        }
        return new TargetDto(type.name(), id, label, EmailTargets.link(type.emailType(), id), false);
    }

    private EffectDto effect(AutomationEffect e) {
        TriggerEntity type = TriggerEntity.valueOf(e.getTargetType());
        return new EffectDto(e.getId(), e.getActionKey(), e.getActionType(), target(type, e.getTargetId(),
                e.getTargetLabel()), e.getStatus().name(), result(e), e.getMessage(), e.warningList(),
                e.getCreatedAt());
    }

    private static EffectDto hiddenEffect(AutomationEffect e) {
        return new EffectDto(e.getId(), e.getActionKey(), e.getActionType(), TargetDto.hiddenOne(e.getTargetType()),
                e.getStatus().name(), null, null, null, e.getCreatedAt());
    }

    private static ResultDto result(AutomationEffect e) {
        if (e.getResultType() == null || e.getResultId() == null) return null;
        String path = switch (e.getResultType()) {
            case "TASK" -> "/tasks/";
            case "PROMISE" -> "/promises/";
            case "DISPUTE" -> "/disputes/";
            case "PENDING_CHANGE" -> "/approvals/";
            case "EMAIL" -> "/emails/";
            default -> null;
        };
        return new ResultDto(e.getResultType(), e.getResultId(), path == null ? null : path + e.getResultId());
    }
}
