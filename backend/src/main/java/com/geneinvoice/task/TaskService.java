package com.geneinvoice.task;

import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.rule.AutomationRuleRepository;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.history.Records;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tasks on customers, invoices and payments (A6, §5.9.2). A task takes its region from its customer, locked in the
 * same transaction (F1), and is seen only by those who can see its record. Whoever holds TASK_MANAGE in its region
 * may do anything to it; an effective assignee may move it along (start, finish, reopen) but not change it.
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    public static final String ENTITY = AssignedTo.TASK;

    static final String NOTIF_ASSIGNED = "TASK_ASSIGNED";
    static final String NOTIF_DONE = "TASK_DONE";

    private static final int VISIBLE_CHUNK = 1000;

    /** Where each status may go; reopening a cancelled task needs TASK_MANAGE. */
    private static final Map<TaskStatus, Set<TaskStatus>> MOVES = Map.of(
            TaskStatus.OPEN, EnumSet.of(TaskStatus.IN_PROGRESS, TaskStatus.DONE, TaskStatus.CANCELLED),
            TaskStatus.IN_PROGRESS, EnumSet.of(TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.CANCELLED),
            TaskStatus.DONE, EnumSet.of(TaskStatus.OPEN),
            TaskStatus.CANCELLED, EnumSet.of(TaskStatus.OPEN));

    private final TaskRepository repository;
    private final TaskTargets taskTargets;
    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final EmailTargets emailTargets;
    private final CustomerRepository customerRepository;
    private final RegionRepository regionRepository;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Records records;
    private final RecordAccess recordAccess;
    private final UserRepository userRepository;
    private final AutomationRuleRepository ruleRepository;
    private final Clock clock;

    /** A task a rule makes: its text already rendered, its assignees already checked when {@code probed}. */
    public record TaskDraft(TaskEntityType entityType, Long entityId, String title, String description,
                            LocalDate dueDate, List<EmailToken> assignees, Long ruleId, Long runId, boolean probed) {}

    /** The task made, and why any assignee asked for was left out. */
    public record Created(Task task, List<String> warnings) {}

    /** The record as an email target, and whom each token would be told about: worked out before the lock. */
    private record Notice(EmailTargets.Target target, Map<String, List<User>> recipients) {}

    /**
     * What a rule's task needs from before the customer is locked (PC-4): the record (read as the owner by the
     * action's record context), the assignees checked, and whom each would be told about.
     */
    public record AutomationPlan(TaskTargets.Target target, List<EmailToken> assignees, Object notice) {}

    // ---- writes ---------------------------------------------------------------------------------------------

    /**
     * A task made by hand. The read-only work comes first (the record, the assignees, whether each person named
     * can see the record and whom to tell); only then is the customer locked and the task written (PC-4). A person who cannot see the
     * record is a 400 against {@code assignees}, and nothing is written.
     */
    @Transactional
    public Task create(TaskDtos.CreateTaskRequest req) {
        ViewScopeHolder.requireLive();
        requireManageSomewhere();
        TaskTargets.Target target = loadTarget(req.entityType(), req.entityId());
        List<EmailToken> tokens = assigneeStore.validate(req.entityType().emailType(), req.assignees(),
                EmailTargets.offeredRoles(req.entityType().emailType()));
        assigneeService.requireVisible(AssignedTo.TASK, tokens, req.entityType().emailType(), target.id(),
                target.regionId());
        return insert(target, req.title().trim(), blankToNull(req.description()), req.dueDate(), tokens,
                Task.ORIGIN_USER, null, null, notice(req.entityType(), target.id(), tokens));
    }

    /**
     * A task made by a rule, as its owner, inside the action's transaction. Assignees the handler already probed are
     * stored as they are; otherwise anyone who cannot see the record is dropped with a warning.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Created createForAutomation(TaskDraft d) {
        ViewScopeHolder.requireLive();
        requireManageSomewhere();
        TaskTargets.Target target = loadTarget(d.entityType(), d.entityId());
        List<EmailToken> tokens = assigneeStore.validate(d.entityType().emailType(), d.assignees(),
                EmailTargets.offeredRoles(d.entityType().emailType()));
        List<String> warnings = new ArrayList<>();
        if (!d.probed()) {
            AssigneeService.Probed probed = assigneeService.probe(AssignedTo.TASK, tokens,
                    d.entityType().emailType(), target.id(), target.regionId());
            tokens = probed.kept();
            warnings.addAll(probed.warnings());
        }
        String title = d.title() == null || d.title().isBlank() ? target.label() : d.title().trim();
        Task task = insert(target, Fit.to(title, TaskTargets.LABEL_MAX), Fit.to(blankToNull(d.description()),
                FieldLimits.TASK_DESCRIPTION), d.dueDate(), tokens, Task.ORIGIN_AUTOMATION,
                d.ruleId(), d.runId(), notice(d.entityType(), target.id(), tokens));
        return new Created(task, warnings);
    }

    /**
     * The read-only half of a rule's task, run before the customer lock (PC-4): whom the assignees (already checked
     * by {@link AssigneeStore#validate} and probed) would be told about. {@code record} is the record as the action's
     * context already read it.
     */
    @Transactional(readOnly = true)
    public AutomationPlan planForAutomation(TaskTargets.Target target, EmailTargets.Target record,
                                            List<EmailToken> assignees) {
        Notice notice = assignees.isEmpty() ? null
                : new Notice(record, assigneeService.recipients(ENTITY, assignees, record));
        return new AutomationPlan(target, List.copyOf(assignees), notice);
    }

    /**
     * A rule's task with its read-only work already done ({@link #planForAutomation}): under the lock only the
     * checks that must see the locked customer, the write, its audit row and the notices remain.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Created createForAutomation(TaskDraft d, AutomationPlan plan) {
        ViewScopeHolder.requireLive();
        requireManageSomewhere();
        TaskTargets.Target target = plan.target();
        String title = d.title() == null || d.title().isBlank() ? target.label() : d.title().trim();
        Task task = insert(target, Fit.to(title, TaskTargets.LABEL_MAX), Fit.to(blankToNull(d.description()),
                FieldLimits.TASK_DESCRIPTION), d.dueDate(), plan.assignees(), Task.ORIGIN_AUTOMATION,
                d.ruleId(), d.runId(), (Notice) plan.notice());
        return new Created(task, List.of());
    }

    private Task insert(TaskTargets.Target target, String title, String description, LocalDate dueDate,
                        List<EmailToken> tokens, String origin, Long ruleId, Long runId, Notice notice) {
        Customer customer = customerRepository.findByIdForUpdate(target.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // Read again under the lock: the copy the record lookup loaded may predate a move that committed since.
        Long regionId = customerRepository.regionIdOf(customer.getId()).orElse(null);
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customer.getId(), regionId, "Customer not found");
        currentUser.requireIn(Privileges.TASK_MANAGE, regionId);
        Long actor = WriteOrigins.actor(currentUser);
        Task task = repository.save(Task.builder()
                .title(title)
                .description(description)
                .status(TaskStatus.OPEN)
                .dueDate(dueDate)
                .entityType(target.type())
                .entityId(target.id())
                .entityLabel(target.label())
                .customerId(customer.getId())
                .region(regionRepository.getReferenceById(regionId))
                .origin(origin)
                .ruleId(ruleId)
                .runId(runId)
                .createdByUserId(actor)
                .build());
        List<EmailToken> added = assigneeStore.replace(ENTITY, task.getId(), tokens);
        auditService.record(ENTITY, task.getId(), "TASK_CREATED", null, snapshot(task), actor, null, null, regionId);
        notifyAssigned(task, added, notice);
        return task;
    }

    /**
     * Title, description, due date and assignees: TASK_MANAGE in the task's region; an assignee may not. As with
     * create, the new assignees are checked and resolved before the locks are taken (PC-4); a person who became
     * new only under the lock (someone else's change in between) is checked there.
     */
    @Transactional
    public Task patch(Long id, TaskDtos.PatchTaskRequest req) {
        // Read as a projection, so the task the lock loads below is read afresh, not a copy from before it.
        TaskRepository.Head seen = repository.headOf(id).orElseThrow(() -> new NotFoundException("Task not found"));
        requireVisible(id);
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.TASK_MANAGE, seen.getRegionId());
        requireWritable(seen.isEntityDeleted());
        EmailEntityType recordType = seen.getEntityType().emailType();
        List<EmailToken> wanted = null;
        Set<String> probed = Set.of();
        Notice notice = null;
        if (req.assignees() != null) {
            wanted = assigneeStore.validate(recordType, req.assignees(), EmailTargets.offeredRoles(recordType));
            Set<String> held = keys(assigneeStore.list(ENTITY, id));
            List<EmailToken> fresh = wanted.stream().filter(t -> !held.contains(AssigneeStore.key(t))).toList();
            assigneeService.requireVisible(AssignedTo.TASK, fresh, recordType, seen.getEntityId(),
                    seen.getRegionId());
            probed = keys(fresh);
            notice = notice(seen.getEntityType(), seen.getEntityId(), fresh);
        }

        Task task = lockForWrite(id);
        requireVersion(task, req.version());
        requireWritable(task.isEntityDeleted());
        Long regionId = Region.idOf(task.getRegion());
        currentUser.requireIn(Privileges.TASK_MANAGE, regionId);
        List<EmailToken> before = assigneeStore.list(ENTITY, id);
        if (wanted != null) {
            Set<String> held = keys(before);
            Set<String> checked = Objects.equals(regionId, seen.getRegionId()) ? probed : Set.of();
            assigneeService.requireVisible(AssignedTo.TASK, wanted.stream()
                    .filter(t -> !held.contains(AssigneeStore.key(t)) && !checked.contains(AssigneeStore.key(t)))
                    .toList(), recordType, task.getEntityId(), regionId);
        }

        TaskDtos.TaskAuditSnapshot old = snapshot(task);
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("title", "must not be blank"));
            }
            task.setTitle(req.title().trim());
        }
        if (req.description() != null) task.setDescription(blankToNull(req.description()));
        if (Boolean.TRUE.equals(req.clearDueDate())) {
            task.setDueDate(null);
        } else if (req.dueDate() != null) {
            task.setDueDate(req.dueDate());
        }
        Long actor = WriteOrigins.actor(currentUser);
        TaskDtos.TaskAuditSnapshot now = snapshot(task);
        if (!now.equals(old)) {
            task = repository.save(task);
            auditService.record(ENTITY, id, "TASK_UPDATED", old, snapshot(task), actor, null, null, regionId);
        }
        if (wanted != null && !keys(wanted).equals(keys(before))) {
            List<EmailToken> added = assigneeStore.replace(ENTITY, id, wanted);
            auditService.record(ENTITY, id, "TASK_ASSIGNEES_CHANGED", keysInOrder(before), keysInOrder(wanted),
                    actor, null, null, regionId);
            notifyAssigned(task, added, notice);
        }
        return task;
    }

    /**
     * Moves a task along. TASK_MANAGE in its region may make any move; an effective assignee may start, finish and
     * reopen it, but not cancel it or bring back a cancelled one. Anyone else is refused (403).
     */
    @Transactional
    public Task changeStatus(Long id, TaskStatus to, Long version) {
        Task task = lockForWrite(id);
        requireVersion(task, version);
        requireWritable(task.isEntityDeleted());
        TaskStatus from = task.getStatus();
        if (to == null) throw new BadRequestException("status is required");
        if (from == to) throw new BadRequestException("The task is already " + words(to));
        if (!MOVES.get(from).contains(to)) {
            throw new BadRequestException("A " + words(from) + " task cannot become " + words(to));
        }
        Long regionId = Region.idOf(task.getRegion());
        if (!currentUser.hasIn(Privileges.TASK_MANAGE, regionId)) {
            // An effective assignee: named, or holding an assigned role, and able to open the task (PC-2).
            boolean assigneeMove = to != TaskStatus.CANCELLED && from != TaskStatus.CANCELLED;
            if (!assigneeMove || !currentUser.hasIn(Privileges.TASK_VIEW, regionId) || !assignedToCaller(id)) {
                throw new AccessDeniedException("Not allowed");
            }
        }
        TaskDtos.TaskAuditSnapshot old = snapshot(task);
        Instant now = clock.instant();
        Long actor = WriteOrigins.actor(currentUser);
        task.setStatus(to);
        if (to == TaskStatus.DONE) {
            task.setCompletedAt(now);
            task.setCompletedByUserId(actor);
        } else {
            task.setCompletedAt(null);
            task.setCompletedByUserId(null);
        }
        task.setCancelledAt(to == TaskStatus.CANCELLED ? now : null);
        task = repository.save(task);
        auditService.record(ENTITY, id, "TASK_STATUS_CHANGED", old, snapshot(task), actor, null, null, regionId);
        if (to == TaskStatus.DONE && Task.ORIGIN_USER.equals(task.getOrigin()) && task.getCreatedByUserId() != null
                && !task.getCreatedByUserId().equals(actor)) {
            Task done = task;
            userRepository.findById(task.getCreatedByUserId()).filter(User::isActive).ifPresent(creator ->
                    notificationService.notify(creator.getId(), NOTIF_DONE, Fit.to("Task done: " + done.getTitle(), 200),
                            done.getEntityLabel(), link(done), regionId));
        }
        return task;
    }

    /**
     * A task to change, locked customer first (§5.11.5): its customer id is read without a lock, the customer row is
     * locked (it may be gone, for a task left on a deleted customer), then the task. One the caller cannot see is
     * missing (404); nothing changes while a past date is viewed.
     */
    private Task lockForWrite(Long id) {
        Long customerId = repository.customerIdOf(id).orElseThrow(() -> new NotFoundException("Task not found"));
        customerRepository.findByIdForUpdate(customerId);
        Task task = repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Task not found"));
        requireVisible(id);
        ViewScopeHolder.requireLive();
        return task;
    }

    private static void requireVersion(Task task, Long version) {
        if (version != null && !version.equals(task.getVersion())) {
            throw new OptimisticLockingFailureException("Task " + task.getId() + " was changed by someone else");
        }
    }

    /** A task whose record was deleted was closed with it (TaskCascade) and stays as it was left. */
    private static void requireWritable(boolean entityDeleted) {
        if (entityDeleted) throw new BadRequestException("The record this task was on has been deleted");
    }

    private void requireManageSomewhere() {
        if (!currentUser.has(Privileges.TASK_MANAGE)) throw new AccessDeniedException("Not allowed");
    }

    /** The record, seen by the caller: lacking its kind's VIEW privilege is a 403, one out of sight a 404. */
    private TaskTargets.Target loadTarget(TaskEntityType type, Long id) {
        if (type == null || id == null) throw new BadRequestException("entityType and entityId are required");
        if (!currentUser.has(type.viewPrivilege())) throw new AccessDeniedException("Not allowed");
        return taskTargets.load(type, id);
    }

    /** The record and whom each token would be told about, read before the lock (§5.9.1); none for no tokens. */
    private Notice notice(TaskEntityType type, Long entityId, List<EmailToken> tokens) {
        if (tokens.isEmpty()) return null;
        EmailTargets.Target target = emailTargets.load(type.emailType(), entityId);
        return new Notice(target, assigneeService.recipients(ENTITY, tokens, target));
    }

    /**
     * Tells the people just assigned who can open the task: the actor is left out of a change they made by hand,
     * while a rule's owner is told of what their rule assigned them. What was worked out before the lock is used
     * unless the task's region has changed since (a move committed in between), when it is worked out again.
     */
    private void notifyAssigned(Task task, List<EmailToken> added, Notice notice) {
        if (added.isEmpty() || task.isEntityDeleted()) return;
        if (notice == null || !Objects.equals(notice.target().regionId(), Region.idOf(task.getRegion()))) {
            notice = new Notice(emailTargets.load(task.getEntityType().emailType(), task.getEntityId()), Map.of());
        }
        Long except = WriteOrigins.current(currentUser).kind() == WriteOrigin.Kind.AUTOMATION ? null
                : WriteOrigins.actor(currentUser);
        assigneeService.notifyAssigned(ENTITY, added, notice.target(), notice.recipients(), except, NOTIF_ASSIGNED,
                Fit.to("Task assigned: " + task.getTitle(), 200), task.getEntityLabel(), link(task));
    }

    private static String link(Task task) {
        return "/tasks/" + task.getId();
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    /** The task, or as of a date the task as it was then; one on a record the caller cannot see is missing. */
    @Transactional(readOnly = true)
    public Task get(Long id) {
        Task task = records.find(Task.class, id).orElseThrow(() -> new NotFoundException("Task not found"));
        requireVisible(id);
        return task;
    }

    /** Visibility is its record's (§5.6.4): always a query, since the record's book decides it. */
    private void requireVisible(Long id) {
        if (!queryExecutor.inScope(Task.class, TableSchemas.TASKS, id,
                scopeResolver.scope(ScopeResolver.Kind.TASK, ScopeResolver.Access.VIEW,
                        ScopeResolver.Reach.GRANTED).predicates())) {
            throw new NotFoundException("Task not found");
        }
    }

    /**
     * Of these tasks, those the caller may open (its record's visibility, §5.6.4; none without TASK_VIEW): what the
     * audit trail of a record takes in as its related tasks.
     */
    @Transactional(readOnly = true)
    public Set<Long> visibleAmong(List<Long> ids) {
        if (ids.isEmpty() || currentUser.isCustomer() || !currentUser.has(Privileges.TASK_VIEW)) return Set.of();
        List<PredicateFactory> scope = scopeResolver.scope(ScopeResolver.Kind.TASK, ScopeResolver.Access.VIEW,
                ScopeResolver.Reach.GRANTED).predicates();
        Set<Long> seen = new HashSet<>();
        for (int from = 0; from < ids.size(); from += VISIBLE_CHUNK) {
            List<Long> chunk = List.copyOf(ids.subList(from, Math.min(from + VISIBLE_CHUNK, ids.size())));
            List<PredicateFactory> among = new ArrayList<>(scope);
            among.add((root, q, cb) -> root.get("id").in(chunk));
            seen.addAll(queryExecutor.ids(Task.class, TableSchemas.TASKS,
                    TableQuery.parseUnpaged(TableSchemas.TASKS, "id,asc", List.of()), among, chunk.size()));
        }
        return seen;
    }

    /** Every task the caller sees, or with {@code mine} those assigned to them; "mine" is never a locked filter (R14). */
    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.TaskDto> page(TableQuery query, boolean mine) {
        ScopeResolver.Scope scope = scope(mine);
        var page = queryExecutor.run(Task.class, TableSchemas.TASKS, query, scope.predicates(), List.of("region"));
        return PageResponse.of(toDtos(page.content()), query, page.total(), scope);
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskSummaryDto summary(TableQuery query, boolean mine) {
        LocalDate today = Today.date();
        Object[] row = queryExecutor.aggregate(Task.class, TableSchemas.TASKS, query, scope(mine).predicates(),
                (root, q, cb) -> {
                    Predicate open = root.get("status").in(TaskStatus.OPEN, TaskStatus.IN_PROGRESS);
                    return List.of(
                            cb.count(root.get("id")),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), TaskStatus.OPEN)),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), TaskStatus.IN_PROGRESS)),
                            Aggregates.countWhen(cb, cb.and(open, cb.lessThan(root.<LocalDate>get("dueDate"), today))),
                            Aggregates.countWhen(cb, cb.and(open, cb.equal(root.get("dueDate"), today))));
                });
        return new TaskDtos.TaskSummaryDto(Aggregates.asLong(row[0]), Aggregates.asLong(row[1]),
                Aggregates.asLong(row[2]), Aggregates.asLong(row[3]), Aggregates.asLong(row[4]));
    }

    /** The nav badge: my tasks still to be done. */
    @Transactional(readOnly = true)
    public TaskDtos.MineCountDto mineCount() {
        return new TaskDtos.MineCountDto(queryExecutor.count(Task.class, TableSchemas.TASKS,
                TableQuery.parseUnpaged(TableSchemas.TASKS, null, List.of("status:in:OPEN,IN_PROGRESS")),
                scope(true).predicates()));
    }

    /** A record tab's badge. */
    @Transactional(readOnly = true)
    public TaskDtos.TaskCountDto count(TaskEntityType type, Long entityId) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.TASKS, null,
                List.of("entityType:eq:" + type.name(), "entityId:eq:" + entityId));
        Object[] row = queryExecutor.aggregate(Task.class, TableSchemas.TASKS, query, scope(false).predicates(),
                (root, q, cb) -> List.of(cb.count(root.get("id")), Aggregates.countWhen(cb,
                        root.get("status").in(TaskStatus.OPEN, TaskStatus.IN_PROGRESS))));
        return new TaskDtos.TaskCountDto(Aggregates.asLong(row[0]), Aggregates.asLong(row[1]));
    }

    /** The ids a bulk action or export may touch: VIEW for an export, MANAGE for a write. */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit, ScopeResolver.Access access) {
        return queryExecutor.ids(Task.class, TableSchemas.TASKS, query,
                scopeResolver.scope(ScopeResolver.Kind.TASK, access, ScopeResolver.Reach.SELECTED).predicates(), limit);
    }

    private ScopeResolver.Scope scope(boolean mine) {
        ScopeResolver.Scope scope = scopeResolver.forTasks();
        if (!mine) return scope;
        Long me = currentUser.require().getId();
        List<PredicateFactory> predicates = new ArrayList<>(scope.predicates());
        predicates.add((root, q, cb) -> AssignedTo.predicate(ENTITY, root, q, cb, me));
        return new ScopeResolver.Scope(predicates, scope.lockedFilters(), scope.coverage());
    }

    /** Whether the caller is one of the task's assignees (named, or holding an assigned role on its record). */
    private boolean assignedToCaller(Long id) {
        Long me = currentUser.require().getId();
        return queryExecutor.inScope(Task.class, TableSchemas.TASKS, id,
                List.of((root, q, cb) -> AssignedTo.predicate(ENTITY, root, q, cb, me)));
    }

    /** Of these tasks, those assigned to the caller: one query. */
    private Set<Long> assignedToCaller(List<Long> ids) {
        if (ids.isEmpty() || currentUser.idOrNull() == null) return Set.of();
        Long me = currentUser.require().getId();
        List<PredicateFactory> scope = List.of(
                (root, q, cb) -> root.get("id").in(ids),
                (root, q, cb) -> AssignedTo.predicate(ENTITY, root, q, cb, me));
        return new HashSet<>(queryExecutor.ids(Task.class, TableSchemas.TASKS,
                TableQuery.parseUnpaged(TableSchemas.TASKS, "id,asc", List.of()), scope, ids.size()));
    }

    // ---- DTOs -----------------------------------------------------------------------------------------------

    /** A task's page: its assignees with the people each means now (as of a date, then). */
    @Transactional(readOnly = true)
    public TaskDtos.TaskDto toDto(Task task) {
        List<EmailToken> tokens = assigneeStore.list(ENTITY, task.getId());
        List<AssigneeDtos.AssigneeDto> assignees;
        AsOfPoint point = AsOf.current();
        if (point != null) {
            assignees = assigneeService.describeAt(tokens, task.getEntityType().emailType(), task.getEntityId(),
                    task.getCustomerId(), point);
        } else {
            EmailTargets.Target target = task.isEntityDeleted() ? null
                    : emailTargets.load(task.getEntityType().emailType(), task.getEntityId());
            assignees = assigneeService.describe(ENTITY, tokens, task.getEntityType().emailType(), target);
        }
        return dtos(List.of(task), Map.of(task.getId(), assignees)).get(0);
    }

    /** A list's rows: people by name and roles by label; one query per kind of thing, not per row. */
    @Transactional(readOnly = true)
    public List<TaskDtos.TaskDto> toDtos(List<Task> tasks) {
        if (tasks.isEmpty()) return List.of();
        Map<Long, List<EmailToken>> tokens = assigneeStore.listAll(ENTITY, tasks.stream().map(Task::getId).toList());
        Map<Long, EmailEntityType> types = new HashMap<>();
        tasks.forEach(t -> {
            types.put(t.getId(), t.getEntityType().emailType());
            tokens.putIfAbsent(t.getId(), List.of());
        });
        return dtos(tasks, assigneeService.describeBriefly(tokens, types));
    }

    private List<TaskDtos.TaskDto> dtos(List<Task> tasks, Map<Long, List<AssigneeDtos.AssigneeDto>> assignees) {
        Set<Long> mine = assignedToCaller(tasks.stream().map(Task::getId).toList());
        Map<Long, String> customerNames = new HashMap<>();
        for (Customer c : records.findAll(Customer.class, tasks.stream().map(Task::getCustomerId).toList())) {
            customerNames.put(c.getId(), c.getName());
        }
        Map<Long, User> creators = new HashMap<>();
        List<Long> creatorIds = tasks.stream().map(Task::getCreatedByUserId).filter(Objects::nonNull).distinct().toList();
        if (!creatorIds.isEmpty()) userRepository.findAllById(creatorIds).forEach(u -> creators.put(u.getId(), u));
        Map<Long, String> ruleNames = new HashMap<>();
        List<Long> ruleIds = tasks.stream().map(Task::getRuleId).filter(Objects::nonNull).distinct().toList();
        if (!ruleIds.isEmpty()) ruleRepository.findAllById(ruleIds).forEach(r -> ruleNames.put(r.getId(), r.getName()));
        LocalDate today = Today.date();
        boolean live = !AsOf.active();
        List<TaskDtos.TaskDto> out = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            Long regionId = Region.idOf(t.getRegion());
            boolean assignedToMe = mine.contains(t.getId());
            boolean canEdit = !t.isEntityDeleted() && recordAccess.can(Privileges.TASK_MANAGE, regionId);
            User creator = creators.get(t.getCreatedByUserId());
            out.add(new TaskDtos.TaskDto(
                    t.getId(), t.getTitle(), t.getDescription(), t.getStatus(), t.getDueDate(),
                    t.getStatus().isOpenWork() && t.getDueDate() != null && t.getDueDate().isBefore(today),
                    t.getEntityType(), t.getEntityId(), t.getEntityLabel(), t.isEntityDeleted(),
                    t.getCustomerId(), customerNames.get(t.getCustomerId()),
                    regionId, Region.codeOf(t.getRegion()), Region.nameOf(t.getRegion()),
                    assignees.getOrDefault(t.getId(), List.of()), assignedToMe,
                    t.getOrigin(),
                    t.getRuleId() == null ? null : new TaskDtos.Ref(t.getRuleId(), ruleNames.get(t.getRuleId())),
                    t.getCreatedByUserId() == null ? null : new TaskDtos.Ref(t.getCreatedByUserId(),
                            creator == null ? null : AssigneeService.name(creator)),
                    t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt(), t.getVersion(),
                    canEdit, live && (canEdit || assignedToMe && !t.isEntityDeleted()
                            && t.getStatus() != TaskStatus.CANCELLED)));
        }
        return out;
    }

    public static TaskDtos.TaskAuditSnapshot snapshot(Task t) {
        return new TaskDtos.TaskAuditSnapshot(t.getId(), t.getTitle(), t.getDescription(), t.getStatus(),
                t.getDueDate(), t.getEntityType(), t.getEntityId(), t.getEntityLabel(), t.isEntityDeleted(),
                t.getCustomerId(), Region.idOf(t.getRegion()), t.getOrigin(), t.getRuleId(), t.getCompletedAt(),
                t.getCompletedByUserId(), t.getCancelledAt());
    }

    private static Set<String> keys(List<EmailToken> tokens) {
        Set<String> keys = new HashSet<>();
        tokens.forEach(t -> keys.add(AssigneeStore.key(t)));
        return keys;
    }

    private static Map<String, Object> keysInOrder(List<EmailToken> tokens) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignees", tokens.stream().map(AssigneeStore::key).toList());
        return out;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String words(TaskStatus status) {
        return status.name().replace('_', ' ').toLowerCase();
    }
}
