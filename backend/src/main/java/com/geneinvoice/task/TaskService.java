package com.geneinvoice.task;

import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.assignee.AssigneeKind;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the task lifecycle (T1). A task is a child of a record it names by kind and id, so
 * everything it needs to know about that record — may this caller see it, what is it called, whose
 * customer is it, who holds each role on it — comes from {@code EmailTargets}, the one place that
 * already answers those questions for every kind (T1).
 *
 * <p>Two things here are deliberately asymmetric. Raising a task needs the caller to be able to
 * see the record, because you cannot give somebody work on something you are not allowed to look
 * at. Reading one back does not: a task is reachable by the person it was given to even when the
 * record's POC book is not theirs, which is the whole point of assigning work to somebody who is
 * not its POC (T2). The list follows the reading rule, and a customer login is held to its own
 * customer as it is everywhere else.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    public static final String ENTITY = "TASK";

    /**
     * The statuses that mean "off the list", derived from {@link TaskStatus#isTerminal()} rather
     * than listed, so the open count and the enum can never disagree (T5).
     */
    private static final List<TaskStatus> TERMINAL =
            Arrays.stream(TaskStatus.values()).filter(TaskStatus::isTerminal).toList();

    private final TaskRepository repository;
    private final AssigneeService assigneeService;
    private final EmailTargets targets;
    private final AuditService auditService;
    private final ScopeResolver scopeResolver;
    private final CurrentUser currentUser;

    // ---- reading ---------------------------------------------------------------

    /**
     * One task, held only to the caller's own customer. The record the task hangs off is
     * deliberately not re-checked here: see the class note on why a task outlives its assignee's
     * reach into the record (T2).
     */
    @Transactional(readOnly = true)
    public Task get(Long id) {
        Task task = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Task not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's task answers exactly as a missing one does, so the id space cannot be
        // walked to learn that somebody else's record exists (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(task.getCustomerId())) {
            throw new NotFoundException("Task not found");
        }
        return task;
    }

    /**
     * The predicates every task list must carry, in the shape the other lists use. Tasks carry no
     * POC of their own, so — as with disputes — only the customer restriction applies: work given
     * to a colleague is still work the team can see.
     *
     * <p>{@code mine} is the "my tasks" list of the PRD. It is the caller's ask, not a scope the
     * server pinned on, so it reports no locked chip.
     */
    public ScopeResolver.Scope scope(Boolean mine) {
        User me = currentUser.require();
        List<PredicateFactory> predicates = new ArrayList<>();
        if (me.getCustomerId() != null) {
            Long own = me.getCustomerId();
            predicates.add((root, q, cb) -> cb.equal(root.get("customerId"), own));
            // A customer login is nobody's assignee — assignees are always internal (A1) — so
            // "my tasks" for one is empty rather than their whole account's work.
            if (Boolean.TRUE.equals(mine)) {
                predicates.add((root, q, cb) -> cb.disjunction());
            }
            return new ScopeResolver.Scope(predicates, List.of());
        }
        if (Boolean.TRUE.equals(mine)) {
            Long meId = me.getId();
            predicates.add((root, q, cb) -> assignedToMe(root, q, cb, meId));
        }
        return new ScopeResolver.Scope(predicates, List.of());
    }

    /**
     * Tasks this caller is on, by any of the three ways an assignee can name them (A2): as
     * themselves, as a seat in the customer's POC book, or as the record's own POC field. None of
     * it is stored against the task — a role row is resolved here and now, so somebody who joined
     * the book this morning finds the work waiting for them and somebody who left stops seeing it.
     */
    private static Predicate assignedToMe(Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long meId) {
        Subquery<Long> mine = q.subquery(Long.class);
        Root<Assignee> a = mine.from(Assignee.class);
        Predicate asPerson = cb.and(
                cb.equal(a.get("kind"), AssigneeKind.USER),
                cb.equal(a.get("userId"), meId));
        Predicate asRole = cb.and(
                cb.equal(a.get("kind"), AssigneeKind.ROLE),
                cb.or(holdsCustomerSeat(mine, cb, a, meId), holdsRecordPoc(mine, cb, root, a, meId)));
        mine.select(cb.literal(1L)).where(
                cb.equal(a.get("ownerType"), AssigneeOwnerType.TASK),
                cb.equal(a.get("ownerId"), root.get("id")),
                cb.or(asPerson, asRole));
        return cb.exists(mine);
    }

    /** A customer-level role: the caller sits in that customer's POC book for the matching kind. */
    private static Predicate holdsCustomerSeat(Subquery<Long> outer, CriteriaBuilder cb,
                                               Root<Assignee> a, Long meId) {
        Subquery<Long> seat = outer.subquery(Long.class);
        Root<CustomerPoc> poc = seat.from(CustomerPoc.class);
        // The two enums spell the same idea differently (SALES_POC / SALES), so the pairing is
        // written out rather than computed in SQL.
        List<Predicate> pairs = new ArrayList<>();
        for (EmailRole role : EmailRole.values()) {
            pairs.add(cb.and(cb.equal(a.get("role"), role), cb.equal(poc.get("pocType"), role.pocType())));
        }
        seat.select(cb.literal(1L)).where(
                cb.equal(poc.get("customer").get("id"), a.get("customerId")),
                cb.equal(poc.get("user").get("id"), meId),
                cb.or(pairs.toArray(new Predicate[0])));
        return cb.and(cb.equal(a.get("level"), RoleLevel.CUSTOMER), cb.exists(seat));
    }

    /**
     * A record-level role: the caller is the POC the record itself names — the invoice's Sales POC
     * or the payment's Collection POC, the only two record-level seats these kinds offer (L3). A
     * customer has none, so a task on one never matches here.
     */
    private static Predicate holdsRecordPoc(Subquery<Long> outer, CriteriaBuilder cb,
                                            Root<?> task, Root<Assignee> a, Long meId) {
        Subquery<Long> invoice = outer.subquery(Long.class);
        Root<Invoice> inv = invoice.from(Invoice.class);
        invoice.select(cb.literal(1L)).where(
                cb.equal(inv.get("id"), task.get("entityId")),
                cb.equal(inv.get("salesPoc").get("id"), meId));

        Subquery<Long> payment = outer.subquery(Long.class);
        Root<Payment> pay = payment.from(Payment.class);
        payment.select(cb.literal(1L)).where(
                cb.equal(pay.get("id"), task.get("entityId")),
                cb.equal(pay.get("collectionPoc").get("id"), meId));

        return cb.and(cb.equal(a.get("level"), RoleLevel.RECORD), cb.or(
                cb.and(cb.equal(task.get("entityType"), TaskEntityType.INVOICE),
                        cb.equal(a.get("role"), EmailRole.SALES_POC), cb.exists(invoice)),
                cb.and(cb.equal(task.get("entityType"), TaskEntityType.PAYMENT),
                        cb.equal(a.get("role"), EmailRole.COLLECTION_POC), cb.exists(payment))));
    }

    /** How many are still live on a record, for the tab's badge. */
    @Transactional(readOnly = true)
    public long openCountFor(TaskEntityType type, Long entityId) {
        return repository.countByEntityTypeAndEntityIdAndStatusNotIn(type, entityId, TERMINAL);
    }

    // ---- raising ---------------------------------------------------------------

    /**
     * Raises a task for a logged-in caller. The record is loaded through the caller's own eyes, so
     * a record they may not see is the 403 or 404 that read already gives — there is no second
     * rule here to fall out of step with it.
     */
    @Transactional
    public Task create(TaskDtos.CreateTaskRequest req) {
        TaskEntityType type = TaskEntityType.parse(req.entityType());
        EmailTargets.Target target = targets.load(type.toEmailEntityType(), req.entityId());
        return write(req, type, target, currentUser.require().getId());
    }

    /**
     * The same, for an automation rule's consumer, which runs on a background thread with nobody
     * logged in (T7, A3). No privilege and no customer restriction is applied, because there is no
     * caller to apply them to: the rule was written by somebody who was allowed to write it, and
     * that is where the decision was taken. Anything serving a request must use {@link #create}.
     *
     * <p>The record may have gone between the rule firing and the task being raised, which is a
     * {@link NotFoundException} rather than a half-built task pointing at nothing.
     */
    @Transactional
    public Task createInBackground(TaskDtos.CreateTaskRequest req) {
        TaskEntityType type = TaskEntityType.parse(req.entityType());
        EmailTargets.Target target = targets.loadInBackground(type.toEmailEntityType(), req.entityId())
                .orElseThrow(() -> new NotFoundException(
                        "The " + type.noun() + " this task is for no longer exists"));
        // Null, not a stand-in user: nobody asked for this task, a rule did (T7).
        return write(req, type, target, currentUser.idOrNull());
    }

    /**
     * The half both creates share. Everything the request carries is validated here rather than
     * left to the controller's bean validation, because the background path arrives from a rule's
     * stored config and never passes through it (T7).
     */
    private Task write(TaskDtos.CreateTaskRequest req, TaskEntityType type,
                       EmailTargets.Target target, Long byUserId) {
        String title = requireTitle(req.title());
        String notes = checkedNotes(req.notes());
        TaskStatus status = req.status() == null || req.status().isBlank()
                ? TaskStatus.OPEN
                : TaskStatus.parse(req.status());
        List<Assignee> rows = assigneeService.parse(
                type.toEmailEntityType(), target.customerId(), req.assignees());

        Task task = Task.builder()
                .entityType(type)
                .entityId(target.id())
                .customerId(target.customerId())
                .title(title)
                .entityLabel(fit(target.label(), Task.LABEL_MAX))
                .dueDate(req.dueDate())
                .status(status)
                .notes(notes)
                .createdByUserId(byUserId)
                .build();
        // A rule may raise work that is already accounted for; stamping it here keeps "finished"
        // and "when" together however the task got there (T4).
        if (status.isTerminal()) {
            task.setCompletedAt(Instant.now());
            task.setCompletedByUserId(byUserId);
        }
        task = repository.save(task);

        // The assignees are written after the task, because they are keyed on its id; both are in
        // this transaction, so a task never commits with half a list on it (A1).
        rows = assigneeService.replace(AssigneeOwnerType.TASK, task.getId(), rows);
        auditService.record(ENTITY, task.getId(), "TASK_CREATED", null, snapshot(task, rows),
                byUserId, null, null);
        return task;
    }

    // ---- changing --------------------------------------------------------------

    /** Edits a task. See {@link TaskDtos.UpdateTaskRequest} for which nulls clear and which leave alone. */
    @Transactional
    public Task update(Long id, TaskDtos.UpdateTaskRequest req) {
        Task task = get(id);
        List<Assignee> rows = assigneeService.of(AssigneeOwnerType.TASK, id);
        Object before = snapshot(task, rows);

        if (req.title() != null && !req.title().isBlank()) {
            task.setTitle(requireTitle(req.title()));
        }
        task.setDueDate(req.dueDate());
        task.setNotes(checkedNotes(req.notes()));

        if (req.status() != null && !req.status().isBlank()) {
            applyStatus(task, TaskStatus.parse(req.status()));
        }
        if (req.assignees() != null) {
            rows = assigneeService.replace(AssigneeOwnerType.TASK, id,
                    assigneeService.parse(task.getEntityType().toEmailEntityType(),
                            task.getCustomerId(), req.assignees()));
        }

        Task saved = repository.save(task);
        auditService.record(ENTITY, id, "TASK_UPDATED", before, snapshot(saved, rows),
                currentUser.idOrNull(), null, null);
        return saved;
    }

    /**
     * Moves the status and keeps the completion stamp honest: finishing a task records who and
     * when, re-opening it takes both off again, and re-sending the status it already has is not a
     * second completion — otherwise editing the notes of a task finished last week would quietly
     * move its completion to today (T4).
     */
    private void applyStatus(Task task, TaskStatus next) {
        if (next == task.getStatus()) return;
        task.setStatus(next);
        if (next.isTerminal()) {
            task.setCompletedAt(Instant.now());
            task.setCompletedByUserId(currentUser.idOrNull());
        } else {
            task.setCompletedAt(null);
            task.setCompletedByUserId(null);
        }
    }

    /**
     * Removes a task raised in error. The row goes for good — there is nothing in it worth keeping
     * a tombstone for, unlike a document's bytes — and the audit trail holds what it said, who
     * raised it and who took it away.
     */
    @Transactional
    public void delete(Long id) {
        Task task = get(id);
        Object before = snapshot(task, assigneeService.of(AssigneeOwnerType.TASK, id));
        repository.delete(task);
        // Replacing with nothing is how the assignee rows go; it flushes first, so the task is
        // already on its way out and no row is left pointing at an id that has gone (A1).
        assigneeService.replace(AssigneeOwnerType.TASK, id, List.of());
        auditService.record(ENTITY, id, "TASK_DELETED", before, null,
                currentUser.idOrNull(), null, null);
    }

    /**
     * Drops a deleted customer's tasks — its own, and those on its invoices and payments (T6).
     * Runs in the customer delete's transaction. The {@code assignees} rows that hang off them are
     * swept once for every kind of owner by {@code AssigneeRepository.deleteForCustomer}, which
     * the customer delete calls, so they are deliberately not touched here.
     */
    @Transactional
    public void onCustomerDeleted(Long customerId) {
        int removed = repository.deleteByCustomerId(customerId);
        if (removed > 0) {
            log.info("Deleted {} task(s) of deleted customer {}", removed, customerId);
        }
    }

    // ---- rendering -------------------------------------------------------------

    /** One task as the caller may see it, with its assignees resolved against the record. */
    @Transactional(readOnly = true)
    public TaskDtos.TaskDto toDto(Task task) {
        List<Assignee> rows = assigneeService.of(AssigneeOwnerType.TASK, task.getId());
        return render(task, rows, targetFor(task, rows).orElse(null), showStaff(), showPoc());
    }

    /**
     * A page of tasks. Two things are batched: the assignees of every row come back in one read
     * rather than one per row, and the record behind them is loaded once per distinct record — and
     * only for rows that actually carry a role, since a task assigned to people by name needs
     * nothing from the record to render (T3).
     */
    @Transactional(readOnly = true)
    public List<TaskDtos.TaskDto> toDtos(List<Task> tasks) {
        if (tasks.isEmpty()) return List.of();
        Map<Long, List<Assignee>> byOwner = assigneeService.byOwner(
                AssigneeOwnerType.TASK, tasks.stream().map(Task::getId).toList());
        boolean showStaff = showStaff();
        boolean showPoc = showPoc();
        Map<String, Optional<EmailTargets.Target>> loaded = new HashMap<>();

        List<TaskDtos.TaskDto> out = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            List<Assignee> rows = byOwner.getOrDefault(task.getId(), List.of());
            EmailTargets.Target target = needsRecord(rows)
                    ? loaded.computeIfAbsent(recordKey(task), key -> targetFor(task, rows)).orElse(null)
                    : null;
            out.add(render(task, rows, target, showStaff, showPoc));
        }
        return out;
    }

    private static boolean needsRecord(List<Assignee> rows) {
        return rows.stream().anyMatch(a -> a.getKind() == AssigneeKind.ROLE);
    }

    private static String recordKey(Task task) {
        return task.getEntityType().name() + ":" + task.getEntityId();
    }

    /**
     * The record a role assignee is read against. Loaded unscoped, and only ever to answer "who
     * does this seat reach right now": the tasks themselves have already been scoped by the read
     * that produced them, and the alternative — a scoped load per row — both re-asks a question
     * already answered and turns one task on a record outside the caller's own book into a failure
     * that would roll the whole read back (T2). A record that has since gone reads as a role
     * nobody holds, which is exactly how an unheld seat reads anyway.
     */
    private Optional<EmailTargets.Target> targetFor(Task task, List<Assignee> rows) {
        if (!needsRecord(rows)) return Optional.empty();
        return targets.loadInBackground(task.getEntityType().toEmailEntityType(), task.getEntityId());
    }

    private TaskDtos.TaskDto render(Task task, List<Assignee> rows, EmailTargets.Target target,
                                    boolean showStaff, boolean showPoc) {
        List<AssigneeDtos.AssigneeDto> assignees = assigneeService.describe(rows, target, showPoc);
        return new TaskDtos.TaskDto(
                task.getId(),
                task.getEntityType().name(),
                task.getEntityId(),
                task.getEntityLabel(),
                task.getEntityType().link(task.getEntityId()),
                task.getCustomerId(),
                task.getTitle(),
                task.getDueDate(),
                task.getStatus(),
                task.getStatus().label(),
                isOverdue(task),
                task.getNotes(),
                assignees,
                showStaff ? task.getCreatedByUserId() : null,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt());
    }

    /**
     * Late only while it still matters: a finished or cancelled task is never overdue, and the day
     * it is due is not yet late — a task due today is due today (T9). Read against the app's own
     * today, the same one invoice ageing uses, so the two never disagree about the date.
     */
    private static boolean isOverdue(Task task) {
        LocalDate due = task.getDueDate();
        return due != null && !task.getStatus().isTerminal() && due.isBefore(InvoiceDates.today());
    }

    /**
     * Who raised a task is staff identity, which customer logins never see (AC-A8). Nobody logged
     * in means a background thread, which is internal by definition, and asking {@code isCustomer()}
     * there would throw rather than answer.
     */
    private boolean showStaff() {
        return currentUser.idOrNull() == null || !currentUser.isCustomer();
    }

    /**
     * Whether a task's assignees may name the people they reach. A role assignee reaches the
     * holders of the customer's POC seats and the record's own POC, which is POC identity and is
     * {@code POC_VIEW}'s to give — the same check the invoice list, the payment list and a
     * promise's assignees make, rather than a second rule that lets a task hand out by name what
     * those three withhold (AC-A6, AC-A8). A customer login never passes it, so this is also the
     * stricter half of {@link #showStaff}.
     */
    private boolean showPoc() {
        return currentUser.idOrNull() == null || scopeResolver.canSeePoc();
    }

    // ---- helpers ---------------------------------------------------------------

    private static String requireTitle(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) throw new BadRequestException("A task needs a title");
        if (title.length() > Task.TITLE_MAX) {
            throw new BadRequestException("A task title is at most " + Task.TITLE_MAX + " characters");
        }
        return title;
    }

    private static String checkedNotes(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > Task.NOTES_MAX) {
            throw new BadRequestException("Task notes are at most " + Task.NOTES_MAX + " characters");
        }
        return raw;
    }

    /** A record's label is a snapshot, and a long one is cut rather than failing the insert. */
    private static String fit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Compact form written into the audit trail, with the assignees as the tokens that made them. */
    private Object snapshot(Task task, List<Assignee> rows) {
        return new TaskAuditSnapshot(task.getId(), task.getEntityType(), task.getEntityId(),
                task.getEntityLabel(), task.getCustomerId(), task.getTitle(), task.getDueDate(),
                task.getStatus(), task.getNotes(), AssigneeService.tokens(rows),
                task.getCompletedByUserId(), task.getCompletedAt());
    }

    private record TaskAuditSnapshot(Long id, TaskEntityType entityType, Long entityId,
                                     String entityLabel, Long customerId, String title,
                                     LocalDate dueDate, TaskStatus status, String notes,
                                     List<String> assignees, Long completedByUserId,
                                     Instant completedAt) {}
}
