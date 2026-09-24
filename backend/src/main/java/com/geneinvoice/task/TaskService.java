package com.geneinvoice.task;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.customer.CustomerHistoryRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionPlacements;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tasks, end to end, with the automation engine nowhere in sight.
 *
 * <p>That is deliberate and it is the whole point of building A6 before A1-A5: when A-CONSUMER
 * arrives it calls exactly ONE method here — {@link #createFromRule} — and the automation layer
 * needs no task logic of its own (A6, A5).
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    public static final String ENTITY = "TASK";

    /** The notification an assignee gets, the PocService.notifyAssignee shape (A6). */
    static final String NOTIF_ASSIGNED = "TASK_ASSIGNED";

    /** What the "due this week" tile means, in days from today inclusive (A6). */
    static final int DUE_SOON_DAYS = 7;

    private final TaskRepository taskRepository;
    private final TaskAssigneeRepository assigneeRepository;
    // The three READ gates. Each already answers 404 for a record outside the caller's book or
    // region, so a task cannot be made about a record the caller cannot see (A6, AUTH-08).
    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    // The same three subjects read WITHOUT a principal, for the rule path only. A daemon thread
    // has no SecurityContext, so the gates above would throw rather than scope; the bound there is
    // RegionScope.asRegions, honoured by regionAccess.requireManage (A6, A5, B1).
    private final CustomerRepository customerRepository;
    // The account's name as it stood then. task_history does not denormalise it — the live
    // row does not either — so it is a lookup on both paths, against the mirror under an
    // as-of date so that a task list and an invoice list of the same January name the same
    // account the same way (B3).
    private final CustomerHistoryRepository customerHistoryRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    // The region chips this list says it is narrowed by; empty for an unregioned table,
    // for a wildcard holder and for a customer login, so it is passed unconditionally (B1).
    private final RegionScope regionScope;
    // The write-side gate. Reads are emptied by the query predicate and read as nonexistent;
    // a write against a branch the caller cannot manage is refused out loud (B1).
    private final RegionAccess regionAccess;
    // No mirror carries region_id, so an as-of row's branch comes from R7's placement ledger.
    // One query for the page, and not one statement on the live path (B3, B1).
    private final RegionPlacements regionPlacements;
    // Says so when a row in this answer was repaired rather than watched happen (B3).
    private final HistoryDrift historyDrift;
    // The SEAT's own branch check, the PocService.requireAssignable pair: the grant table because
    // the subject is not the caller, and the region code because the refusal names the branch
    // rather than the id (A6, B1).
    private final UserRegionGrantRepository userRegionGrantRepository;
    private final RegionRepository regionRepository;

    /** What a task is about, once resolved: the Document polymorphic shape (A6). */
    private record Subject(TaskEntityType type, Long id, String label, Long customerId,
                           Long regionId) {}

    // ---- writes ---------------------------------------------------------------------------

    @Transactional
    public TaskDtos.TaskDto create(TaskDtos.CreateTaskRequest req) {
        if (req.entityType() == null || req.entityId() == null) {
            throw new BadRequestException("A task needs a record to be about");
        }
        // The READ gate first: customerService.get / invoiceService.get / paymentService.get each
        // carry the book and the region axis, so a record this caller cannot see answers 404 here
        // exactly as it does on its own screen (A6, AUTH-08).
        Subject subject = visibleSubject(req.entityType(), req.entityId());
        // Then the WRITE gate, in THIS body: making a record is MANAGE where the account lives,
        // and 403 rather than 404 because the branch was reached by naming a record the caller can
        // already see (A6, B1, D-46).
        regionAccess.requireManage(subject.regionId());

        return toDto(write(currentUser.require().getId(), null, null, subject,
                req.title(), req.notes(), req.dueDate(),
                req.assigneeUserIds(), sourcesFor(req.assigneeUserIds())));
    }

    /**
     * Creating a task on behalf of a named actor: the ONE method A-CONSUMER calls (A6, A5).
     *
     * <p>Every guard on creating a task belongs in THIS body and never in a delegating wrapper.
     * {@link #create} is not a wrapper around this method and this method is not a wrapper around
     * that one: they resolve the subject differently on purpose, because a rule runs on a daemon
     * thread with no SecurityContext at all and the principal-based READ gates would throw rather
     * than scope. A check written on a one-line delegate is a check the other path walks straight
     * past, which is the S4 rule (A5, B1 INTEGRATION).
     *
     * <p>The actor is the RULE'S AUTHOR, a real accountable person, never null and never
     * synthetic: a null maker makes "approval from somebody else" trivially satisfiable and voids
     * maker-checker (A5, B2).
     *
     * <p>requireManage below is a BOUND and not a bypass. On the consumer's thread
     * RegionScope.asRegions has REPLACED the grant-derived region set with the rule's own regions,
     * and RegionAccess honours it in preference to a caller — so a subject outside the rule's
     * regions is refused here just as a person outside their grants would be (A5, B1).
     */
    @Transactional
    public Task createFromRule(Long actorUserId, Long ruleId, Long stepId, TaskEntityType type,
                               Long entityId, String title, String notes, LocalDate dueDate,
                               List<Long> assigneeUserIds, List<String> sources) {
        if (actorUserId == null) {
            throw new BadRequestException("An automated task needs the rule's author as its actor");
        }
        if (type == null || entityId == null) {
            throw new BadRequestException("A task needs a record to be about");
        }
        Subject subject = unscopedSubject(type, entityId);
        regionAccess.requireManage(subject.regionId());
        return write(actorUserId, ruleId, stepId, subject, title, notes, dueDate,
                assigneeUserIds, sources);
    }

    /**
     * The shared tail: the data rules, the row, the seats, the telling and the audit trail.
     *
     * <p>Only rules that hold whoever the caller is live here — the title's shape, an assignee
     * having to be an active internal person, the notification that skips the actor. The gates
     * that depend on WHO is asking stay in the two public bodies above, because a gate on a shared
     * tail is one nobody reads when they add a third entry point (A6, A5).
     */
    private Task write(Long actorUserId, Long ruleId, Long stepId, Subject subject,
                       String title, String notes, LocalDate dueDate,
                       List<Long> assigneeUserIds, List<String> sources) {
        Task task = taskRepository.save(Task.builder()
                .entityType(subject.type())
                .entityId(subject.id())
                .entityLabel(subject.label())
                .customerId(subject.customerId())
                .title(requireTitle(title))
                .notes(fit(Strings.blankToNull(notes), FieldLimits.TASK_NOTES))
                .dueDate(dueDate)
                .status(TaskStatus.OPEN)
                .createdByUserId(actorUserId)
                .createdByRuleId(ruleId)
                .createdByStepId(stepId)
                .build());

        List<TaskAssignee> seats = setAssignees(task, subject.regionId(), assigneeUserIds, sources);
        for (TaskAssignee seat : seats) {
            notifyAssignee(actorUserId, task, seat.getUserId());
        }

        auditService.record(ENTITY, task.getId(), "TASK_CREATED", null, snapshot(task, seats),
                actorUserId, null, null);
        return task;
    }

    @Transactional
    public TaskDtos.TaskDto update(Long id, TaskDtos.UpdateTaskRequest req) {
        Task task = get(id);
        // get() has already answered "may I reach this row" with 404; CHANGING it needs MANAGE
        // where the account lives (A6, B1, AUTH-08, D-46).
        regionAccess.requireManage(regionOf(task));
        Long actor = currentUser.require().getId();
        Object before = snapshot(task, assigneeRepository.findByTaskIdOrderByIdAsc(id));
        boolean wasOpen = !task.getStatus().terminal();

        if (req.title() != null) task.setTitle(requireTitle(req.title()));
        // notes and dueDate are replaced by whatever arrived, null included: "this no longer has a
        // due date" has to be expressible and Jackson cannot tell absent from null (A6).
        task.setNotes(fit(Strings.blankToNull(req.notes()), FieldLimits.TASK_NOTES));
        task.setDueDate(req.dueDate());
        if (req.status() != null) applyStatus(task, req.status(), actor);

        List<TaskAssignee> seats;
        if (req.assigneeUserIds() == null) {
            seats = assigneeRepository.findByTaskIdOrderByIdAsc(id);
        } else {
            Set<Long> already = seats(id);
            seats = setAssignees(task, regionOf(task), req.assigneeUserIds(), null);
            for (TaskAssignee seat : seats) {
                // Only somebody who was not already on it: re-saving a task must not tell the same
                // people again, which is how a notification list becomes noise nobody reads (A6).
                if (!already.contains(seat.getUserId())) notifyAssignee(actor, task, seat.getUserId());
            }
        }

        Task saved = taskRepository.save(task);
        // A PATCH that sets DONE finished the task, so it writes the same row a press of Complete
        // would: one audit action per thing that happened, not per door it came through (A6).
        String action = saved.getStatus() == TaskStatus.DONE && saved.getCompletedAt() != null
                && wasOpen ? "TASK_COMPLETED" : "TASK_UPDATED";
        auditService.record(ENTITY, id, action, before, snapshot(saved, seats), actor, null, null);
        return toDto(saved, seats);
    }

    @Transactional
    public TaskDtos.TaskDto complete(Long id) {
        Task task = get(id);
        regionAccess.requireManage(regionOf(task));
        if (task.getStatus() == TaskStatus.DONE) {
            throw new BadRequestException("This task is already done");
        }
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new BadRequestException("A cancelled task cannot be completed");
        }
        Long actor = currentUser.require().getId();
        List<TaskAssignee> seats = assigneeRepository.findByTaskIdOrderByIdAsc(id);
        Object before = snapshot(task, seats);

        applyStatus(task, TaskStatus.DONE, actor);
        Task saved = taskRepository.save(task);
        auditService.record(ENTITY, id, "TASK_COMPLETED", before, snapshot(saved, seats),
                actor, null, null);
        return toDto(saved, seats);
    }

    @Transactional
    public TaskDtos.TaskDto cancel(Long id) {
        Task task = get(id);
        regionAccess.requireManage(regionOf(task));
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new BadRequestException("This task is already cancelled");
        }
        if (task.getStatus() == TaskStatus.DONE) {
            throw new BadRequestException("A task that is already done cannot be cancelled");
        }
        Long actor = currentUser.require().getId();
        List<TaskAssignee> seats = assigneeRepository.findByTaskIdOrderByIdAsc(id);
        Object before = snapshot(task, seats);

        applyStatus(task, TaskStatus.CANCELLED, actor);
        Task saved = taskRepository.save(task);
        // TASK_UPDATED and not a fourth action string: the blueprint fixes the three this feature
        // may write, and a cancel IS an update to the status somebody can read in the blob (A6).
        auditService.record(ENTITY, id, "TASK_UPDATED", before, snapshot(saved, seats),
                actor, null, "Cancelled");
        return toDto(saved, seats);
    }

    /**
     * THE OTHER HALF OF THE SEAT'S BRANCH RULE: THE ACCOUNT MOVED (A6, B1).
     *
     * <p>A seat is only valid while its holder can MANAGE the account's branch, and a move changes
     * the branch under seats that were valid when they were written. PocService vacates its seats
     * on exactly this event and for exactly this reason; a task seat was the one B1 never revisited,
     * so moving an account left every assignee in the branch it came from holding open work that
     * now answers 404 to them.
     *
     * <p>ONLY WORK STILL OWED. A finished or cancelled task records WHO DID IT, and stripping the
     * name off it would rewrite that; only a task that is still open is work somebody can no
     * longer do.
     *
     * <p>NOBODY IS NOTIFIED, deliberately: a notification about a task the reader can no longer
     * open is the defect this closes, spelled the other way round. The audit row on the task is
     * where a move's effect on the work belongs, and the move writes its own row on the account.
     */
    @Transactional
    public int vacateSeatsWhoseHolderCannotManage(Long customerId, Long toRegionId) {
        int vacated = 0;
        for (Task task : taskRepository.findByCustomerId(customerId)) {
            if (task.getStatus().terminal()) continue;
            List<TaskAssignee> seats = assigneeRepository.findByTaskIdOrderByIdAsc(task.getId());
            List<TaskAssignee> going = seats.stream()
                    .filter(seat -> !userRegionGrantRepository
                            .covers(seat.getUserId(), toRegionId, RegionRight.MANAGE))
                    .toList();
            if (going.isEmpty()) continue;
            Object before = snapshot(task, seats);
            assigneeRepository.deleteAll(going);
            // task_assignees.task_id is the one foreign key in the programme and Hibernate runs
            // every INSERT ahead of every DELETE in a flush: the seats leave the database here,
            // before anything else in the move's transaction can write over them (A6).
            assigneeRepository.flush();
            List<TaskAssignee> left = seats.stream().filter(seat -> !going.contains(seat)).toList();
            auditService.record(ENTITY, task.getId(), "TASK_UPDATED", before,
                    snapshot(task, left), currentUser.idOrNull(), null,
                    "Seat vacated: the account moved to a branch this person does not work in");
            vacated += going.size();
        }
        return vacated;
    }

    @Transactional
    public TaskDtos.TaskDto reassign(Long id, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BadRequestException("REASSIGN requires at least one person");
        }
        return update(id, new TaskDtos.UpdateTaskRequest(null, null, null, null, userIds));
    }

    // ---- reads ----------------------------------------------------------------------------

    /**
     * One task by id, or 404 — never 403. A record the caller's book or region excludes answers
     * exactly as a record that does not exist, or the difference is an oracle for which ids exist
     * (AUTH-08).
     */
    @Transactional(readOnly = true)
    public Task get(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Task not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(task.getCustomerId())) {
            throw new NotFoundException("Task not found");
        }
        if (!queryExecutor.inScope(Task.class, TaskSchemas.TASKS, id,
                scopeResolver.forTasks().predicates())) {
            throw new NotFoundException("Task not found");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskDto dto(Long id) {
        if (!AsOfContext.isActive()) {
            Task task = get(id);
            return toDto(task, assigneeRepository.findByTaskIdOrderByIdAsc(id));
        }
        // ONE TASK AS IT STOOD ON THE DATE ASKED ABOUT — and 404, never 403, when it did not exist
        // then. The InvoiceService.detail shape: the same mirror, the same executor, the same
        // scope list and an id:eq: filter, so there is no second copy of get()'s checks (B3).
        Long callerCustomer = currentUser.customerIdOrNull();
        AsOfSource<TaskView> source = taskSource();
        List<? extends TaskView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()
                || (callerCustomer != null && !callerCustomer.equals(rows.get(0).getCustomerId()))) {
            throw new NotFoundException("Task not found");
        }
        markDrift();
        return toDtos(rows).get(0);
    }

    /**
     * WHERE A TASK LIST READS FROM (B3). The InvoiceService.invoiceSource() shape exactly: live it
     * is the tasks table, its live schema and A6's book; under {@code ?asOf} it is the interval
     * mirror, its as-of twin, and the SAME book — because B3-BOOKROOT and B3-SCHEMAS made
     * taskCustomerInBook resolve on both roots.
     *
     * <p>{@code AsOf.at(T)} LEADS THE SCOPE LIST and is the line that makes the list count TASKS
     * rather than edits of tasks. The region axis is injected once by the executor for every root
     * and is already as-of correct (blueprint conflict 1, B1, B3).
     *
     * <p>WHO IS ON THE TASK IS STILL TODAY'S ANSWER, and the wire says so: task_assignees is not a
     * mirrored table, so the assignee filter and the assignee list both read the live seats
     * whatever date is asked. HistorySchemas.TASKS marks that column CURRENT (A6, B3).
     */
    public AsOfSource<TaskView> taskSource() {
        ScopeResolver.Scope book = scopeResolver.forTasks();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(Task.class, TaskSchemas.TASKS, book.predicates(),
                    book.lockedFilters(), List.of());
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(TaskHistory.class, HistorySchemas.TASKS,
                List.copyOf(scope), List.copyOf(locked), List.of());
    }

    /** Guarded outside the call because AsOfContext.instant() throws when nothing is open (B3). */
    private void markDrift() {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(TaskHistory.class, AsOfContext.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.TaskDto> page(TableQuery query) {
        AsOfSource<TaskView> source = taskSource();
        var page = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch());
        markDrift();
        return PageResponse.of(toDtos(page.content()), query, page.total(),
                source.locked(), regionScope.lockedFilters(source.type()));
    }

    /**
     * The tiles, through the SAME filters and the SAME scope the list ran, inside ONE aggregate —
     * so what a tile says and what the rows behind the filter chip are cannot disagree (A6).
     */
    @Transactional(readOnly = true)
    public TaskDtos.TaskSummaryTiles tiles(TableQuery query) {
        AsOfSource<TaskView> source = taskSource();
        // As-of aware since B3-CONTEXT, with no edit here: under an open context this IS the date
        // asked for, so taskOverdue and taskDueWithin below age the tiles against that date rather
        // than against today. The five selection lambdas are untouched — every one of them names
        // an attribute the mirror spells the same way and types the same way (B3).
        LocalDate today = InvoiceDates.today();
        markDrift();
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(), query,
                source.scope(), (root, q, cb) -> List.of(
                        Aggregates.countWhen(cb, cb.equal(root.get("status"), TaskStatus.OPEN)),
                        Aggregates.countWhen(cb, cb.equal(root.get("status"), TaskStatus.IN_PROGRESS)),
                        // The same two shapes the overdue chip and the my-work list use, so the
                        // tile is the count of exactly the rows the chip would show (A6).
                        Aggregates.countWhen(cb, TaskSchemas.taskOverdue(root, cb, today)),
                        Aggregates.countWhen(cb,
                                TaskSchemas.taskDueWithin(root, cb, today, DUE_SOON_DAYS)),
                        Aggregates.countWhen(cb, cb.equal(root.get("status"), TaskStatus.DONE))));
        return new TaskDtos.TaskSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asLong(row[1]), Aggregates.asLong(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]));
    }

    /**
     * The sidebar badge. Through queryExecutor.count and NEVER a repository countBy…, so it
     * inherits the book and the region axis rather than becoming an eleventh scope-bypassing
     * endpoint (A6, B1).
     */
    @Transactional(readOnly = true)
    public long count(boolean mine) {
        List<String> filters = new ArrayList<>();
        filters.add("status:in:" + TaskStatus.OPEN + "," + TaskStatus.IN_PROGRESS);
        if (mine) filters.add("assigneeUserId:eq:" + currentUser.require().getId());
        return queryExecutor.count(Task.class, TaskSchemas.TASKS,
                TableQuery.parseUnpaged(TaskSchemas.TASKS, null, filters),
                scopeResolver.forTasks().predicates());
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<TaskView> source = taskSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.TaskDto> allMatching(TableQuery query) {
        AsOfSource<TaskView> source = taskSource();
        List<? extends TaskView> rows = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch()).content();
        markDrift();
        return toDtos(rows);
    }

    // ---- mapping --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TaskDtos.TaskDto toDto(TaskView t) {
        return toDto(t, assigneeRepository.findByTaskIdOrderByIdAsc(t.getId()));
    }

    private TaskDtos.TaskDto toDto(TaskView t, List<TaskAssignee> seats) {
        return toDto(t, seats, names(seats.stream().map(TaskAssignee::getUserId).toList()),
                accounts(List.of(t.getCustomerId())));
    }

    /**
     * What a task row says about the account it is filed against: its name and its branch (A6, B3).
     *
     * <p>A record and not two maps, because under {@code ?asOf} the two come from DIFFERENT places
     * — the name from the account mirror and the branch from R7's placement ledger — while live
     * they both come off one loaded Customer. Passing them as one value is what stops a caller
     * filling one and forgetting the other.
     */
    private record AccountLabel(String name, Long regionId, String regionName) {}

    /** A whole page in three queries rather than three per row (A6). */
    private List<TaskDtos.TaskDto> toDtos(List<? extends TaskView> tasks) {
        if (tasks.isEmpty()) return List.of();
        List<Long> ids = tasks.stream().map(TaskView::getId).toList();
        Map<Long, List<TaskAssignee>> byTask = new LinkedHashMap<>();
        // THE SEATS ARE TODAY'S ON BOTH PATHS, and the schema says so: task_assignees is not a
        // mirrored table, so who is on a task is the only part of this row that cannot be answered
        // as of a date. HistorySchemas.TASKS marks the column CURRENT (A6, B3).
        for (TaskAssignee seat : assigneeRepository.findByTaskIdInOrderByTaskIdAscIdAsc(ids)) {
            byTask.computeIfAbsent(seat.getTask().getId(), k -> new ArrayList<>()).add(seat);
        }
        Map<Long, User> people = names(byTask.values().stream()
                .flatMap(List::stream).map(TaskAssignee::getUserId).toList());
        Map<Long, AccountLabel> accounts =
                accounts(tasks.stream().map(TaskView::getCustomerId).toList());
        return tasks.stream()
                .map(t -> toDto(t, byTask.getOrDefault(t.getId(), List.of()), people, accounts))
                .toList();
    }

    /**
     * The parameter is the VIEW and not the entity, so ONE mapper serves the live row and the as-of
     * mirror row and the two cannot drift apart. Source-compatible — Task implements TaskView, so
     * no call site moves. A task carries its account as a flat customerId with no association, so
     * the widening costs nothing here (B3).
     */
    private TaskDtos.TaskDto toDto(TaskView t, List<TaskAssignee> seats, Map<Long, User> people,
                                   Map<Long, AccountLabel> accounts) {
        // A customer login is told what work is outstanding on its own records and not WHO in the
        // supplier's staff is doing it: the names of internal people are the same thing the POC
        // columns are pocRestricted for (A6, AUTH-08).
        boolean showStaff = !currentUser.isCustomer();
        AccountLabel account = accounts.get(t.getCustomerId());
        return new TaskDtos.TaskDto(
                t.getId(), t.getEntityType(), t.getEntityId(), t.getEntityLabel(),
                t.getCustomerId(), account == null ? null : account.name(),
                t.getTitle(), t.getNotes(), t.getDueDate(), t.getStatus(),
                isOverdue(t, InvoiceDates.today()),
                showStaff ? seats.stream().map(s -> assignee(s, people.get(s.getUserId()))).toList()
                        : List.of(),
                showStaff ? t.getCreatedByUserId() : null,
                showStaff ? t.getCreatedByRuleId() : null,
                showStaff ? t.getCreatedByStepId() : null,
                showStaff ? t.getCompletedByUserId() : null,
                t.getCompletedAt(), t.getCreatedAt(), t.getUpdatedAt(),
                // customers.region_id is NOT NULL, so an account that is here has a branch (B1) —
                // but an account with no PLACEMENT in force on the date asked about reports null,
                // which is the existing "not asked" convention (B3).
                account == null ? null : account.regionId(),
                account == null ? null : account.regionName());
    }

    private static TaskDtos.AssigneeDto assignee(TaskAssignee seat, User u) {
        return new TaskDtos.AssigneeDto(seat.getUserId(),
                u == null ? null : u.getFullName(),
                u == null ? null : u.getUsername(),
                seat.getSource());
    }

    /** Late and not finished, the Java twin of TaskSchemas.taskOverdue (A6). */
    static boolean isOverdue(TaskView t, LocalDate today) {
        return t.getDueDate() != null && t.getDueDate().isBefore(today)
                && !t.getStatus().terminal();
    }

    // ---- the pieces -----------------------------------------------------------------------

    private Subject visibleSubject(TaskEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> of(customerService.get(id));
            case INVOICE -> of(invoiceService.get(id));
            case PAYMENT -> of(paymentService.get(id));
        };
    }

    private Subject unscopedSubject(TaskEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> of(customerRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Customer not found")));
            case INVOICE -> of(invoiceRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Invoice not found")));
            case PAYMENT -> of(paymentRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Payment not found")));
        };
    }

    // .getId() on the lazy Region proxy initialises nothing (B1).
    private static Subject of(Customer c) {
        return new Subject(TaskEntityType.CUSTOMER, c.getId(), label("Customer " + c.getName()),
                c.getId(), c.getRegion().getId());
    }

    private static Subject of(Invoice i) {
        return new Subject(TaskEntityType.INVOICE, i.getId(), label("Invoice " + i.getInvoiceNumber()),
                i.getCustomer().getId(), i.getCustomer().getRegion().getId());
    }

    private static Subject of(Payment p) {
        return new Subject(TaskEntityType.PAYMENT, p.getId(), label("Payment #" + p.getId()),
                p.getCustomer().getId(), p.getCustomer().getRegion().getId());
    }

    /**
     * Replace the seats on a task with exactly this set of people, in the order they were named.
     *
     * <p>The set is de-duplicated before anything is written: uk_task_assignee would refuse the
     * second row anyway, but it would do it as a 500 out of the flush rather than as the quiet
     * "they are already on it" this is (A6).
     */
    private List<TaskAssignee> setAssignees(Task task, Long regionId, List<Long> userIds,
                                           List<String> sources) {
        List<TaskAssignee> existing = task.getId() == null
                ? List.of() : assigneeRepository.findByTaskIdOrderByIdAsc(task.getId());
        if (userIds == null) return existing;

        List<Long> wanted = new ArrayList<>(new LinkedHashSet<>(
                userIds.stream().filter(Objects::nonNull).toList()));
        Map<Long, TaskAssignee> keep = new LinkedHashMap<>();
        for (TaskAssignee seat : existing) keep.put(seat.getUserId(), seat);

        List<TaskAssignee> going = existing.stream()
                .filter(s -> !wanted.contains(s.getUserId())).toList();
        if (!going.isEmpty()) {
            assigneeRepository.deleteAll(going);
            // The seats have to leave the database before their replacements arrive, or a person
            // taken off and put back in one save trips uk_task_assignee — Hibernate runs every
            // INSERT ahead of every DELETE in a flush, and Postgres checks the key at statement
            // time where H2's absence of a partial index would have hidden it (A6).
            assigneeRepository.flush();
        }

        List<TaskAssignee> seats = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            Long userId = wanted.get(i);
            TaskAssignee seat = keep.get(userId);
            if (seat != null) {
                seats.add(seat);
                continue;
            }
            requireAssignable(userId, regionId);
            seats.add(assigneeRepository.save(TaskAssignee.builder()
                    .task(task).userId(userId)
                    .source(source(sources, i))
                    .build()));
        }
        return seats;
    }

    private static String source(List<String> sources, int index) {
        String given = sources == null || index >= sources.size() ? null : sources.get(index);
        String trimmed = Strings.blankToNull(given);
        if (trimmed == null) return TaskAssignee.SOURCE_USER;
        return trimmed.length() <= TaskAssignee.SOURCE_MAX
                ? trimmed : trimmed.substring(0, TaskAssignee.SOURCE_MAX);
    }

    /**
     * A task is owed by a person who works here. The EmailAddressing.activeInternalUser rule and
     * its exact words: a customer login can never own a task, because a customer cannot be given
     * work in the supplier's own book, and a deactivated person would be given work nobody would
     * ever see (A6).
     */
    private void requireAssignable(Long userId, Long regionId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User #" + userId + " does not exist"));
        if (!u.isActive() || u.getCustomerId() != null) {
            throw new BadRequestException(u.getUsername() + " is not an active internal user");
        }
        // AND THEY HAVE TO WORK IN THE BRANCH THE ACCOUNT IS IN, which is the half this rule was
        // missing: Task is VIA_CUSTOMER_ID, so TableQueryExecutor ANDs the region axis OVER the
        // assignee arm of ScopeResolver.forTasks and a seat held by somebody with no grant here
        // is a seat on a row that answers 404 to its own holder — work written, notified and
        // unreachable. MANAGE and not VIEW, the level PocService.requireAssignable uses and the
        // level completing or reassigning the task needs, so a seat always comes with the right
        // to finish it (A6, B1, AUTH-08).
        //
        // A 400 and not a 403, for PocService's reason: the caller is being told something about
        // the PERSON they named and not about their own reach (B1, D-46).
        if (regionId != null
                && !userRegionGrantRepository.covers(userId, regionId, RegionRight.MANAGE)) {
            throw new BadRequestException("User " + u.getUsername() + " does not work in "
                    + regionRepository.codeOf(regionId) + " and cannot be given work there");
        }
    }

    /** Tell somebody they have been given work, skipping the person who gave it to them (A6). */
    private void notifyAssignee(Long actorUserId, Task task, Long userId) {
        if (userId == null || userId.equals(actorUserId)) return;
        notificationService.notify(userId, NOTIF_ASSIGNED, "You have a new task",
                task.getTitle() + " — " + task.getEntityLabel(), "/tasks/" + task.getId());
    }

    private void applyStatus(Task task, TaskStatus status, Long actor) {
        if (task.getStatus() == status) return;
        task.setStatus(status);
        if (status == TaskStatus.DONE) {
            // Who finished it and when, which is the whole question a completed task answers (A6).
            task.setCompletedByUserId(actor);
            task.setCompletedAt(Instant.now());
        } else {
            // Reopening a task un-finishes it: leaving the stamp behind would say somebody
            // completed a task that is open again (A6).
            task.setCompletedByUserId(null);
            task.setCompletedAt(null);
        }
    }

    private Set<Long> seats(Long taskId) {
        return assigneeRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .map(TaskAssignee::getUserId).collect(Collectors.toSet());
    }

    private Long regionOf(Task task) {
        Customer c = customerRepository.findById(task.getCustomerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        return c.getRegion().getId();
    }

    private Map<Long, User> names(Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(new LinkedHashSet<>(userIds))) byId.put(u.getId(), u);
        return byId;
    }

    /**
     * The accounts a page of tasks names, once, for their names and their branches (A6).
     *
     * <p>UNDER {@code ?asOf} BOTH HALVES MOVE, and they move to two different places. The name is
     * the account mirror's name in force at T, because a renamed account must read on a January
     * page the way it read in January — the same rule the denormalised customerName on an invoice
     * follows. The branch is R7's placement ledger at that date and NOT customer_history's own
     * region_id, which blueprint conflict 1 struck as the region axis: the ledger is the single
     * authority for where an account was on a date, and it is the same lookup the executor's own
     * region predicate makes (B3, B1).
     */
    private Map<Long, AccountLabel> accounts(Collection<Long> customerIds) {
        Set<Long> ids = new LinkedHashSet<>(customerIds);
        ids.remove(null);
        Map<Long, AccountLabel> byId = new LinkedHashMap<>();
        if (ids.isEmpty()) return byId;
        if (!AsOfContext.isActive()) {
            for (Customer c : customerRepository.findAllById(ids)) {
                // .getId()/.getName() on the lazy Region proxy is the pre-existing read (B1).
                byId.put(c.getId(), new AccountLabel(c.getName(),
                        c.getRegion().getId(), c.getRegion().getName()));
            }
            return byId;
        }
        Map<Long, RegionPlacements.Placement> placements =
                regionPlacements.at(ids, AsOfContext.date());
        for (CustomerHistory c : customerHistoryRepository.inForce(ids, AsOfContext.instant())) {
            RegionPlacements.Placement placed = placements.get(c.getId());
            byId.put(c.getId(), new AccountLabel(c.getName(),
                    placed == null ? null : placed.regionId(),
                    placed == null ? null : placed.regionName()));
        }
        return byId;
    }

    private static String requireTitle(String raw) {
        String title = Strings.blankToNull(raw);
        if (title == null) throw new BadRequestException("A task needs a title");
        return fit(title, FieldLimits.TASK_TITLE);
    }

    private static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    private static String label(String text) {
        return text.length() <= Task.LABEL_MAX ? text : text.substring(0, Task.LABEL_MAX - 1) + "…";
    }

    private Object snapshot(Task t, List<TaskAssignee> seats) {
        return new TaskAuditSnapshot(t.getId(), t.getEntityType(), t.getEntityId(),
                t.getEntityLabel(), t.getCustomerId(), t.getTitle(), t.getNotes(), t.getDueDate(),
                t.getStatus(), seats.stream().map(TaskAssignee::getUserId).sorted().toList(),
                t.getCreatedByRuleId(), t.getCompletedByUserId(), t.getCompletedAt());
    }

    public record TaskAuditSnapshot(Long id, TaskEntityType entityType, Long entityId,
                                    String entityLabel, Long customerId, String title, String notes,
                                    LocalDate dueDate, TaskStatus status, List<Long> assigneeUserIds,
                                    Long createdByRuleId, Long completedByUserId,
                                    Instant completedAt) {}

    /** Somebody picked these people by name, so every seat's source is "USER" (A6). */
    private static List<String> sourcesFor(List<Long> userIds) {
        return userIds == null ? List.of()
                : userIds.stream().map(u -> TaskAssignee.SOURCE_USER).toList();
    }
}
