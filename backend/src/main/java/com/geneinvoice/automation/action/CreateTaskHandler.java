package com.geneinvoice.automation.action;

import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.task.TaskStatus;
import com.geneinvoice.task.TaskTargets;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CREATE_TASK (§5.10.5): a task on the record, its text rendered and its assignees probed before any lock. */
@Component
@RequiredArgsConstructor
public class CreateTaskHandler implements ActionHandler<CreateTaskAction> {

    public static final String RESULT = "TASK";
    public static final int OFFSET_MIN = -30;
    public static final int OFFSET_MAX = 365;

    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final TaskService taskService;
    private final CurrentUser currentUser;

    @PersistenceContext
    private EntityManager em;

    @Override
    public ActionType type() {
        return ActionType.CREATE_TASK;
    }

    @Override
    public Class<CreateTaskAction> actionClass() {
        return CreateTaskAction.class;
    }

    @Override
    public CreateTaskAction normalize(CreateTaskAction a, TriggerEntity entity, String path,
                                      Map<String, String> errors) {
        ActionSupport.text(a.title(), true, FieldLimits.TASK_TITLE, entity, path + ".title", errors);
        ActionSupport.text(a.description(), false, FieldLimits.TASK_DESCRIPTION, entity, path + ".description", errors);
        ActionSupport.due(a.due(), entity, OFFSET_MIN, OFFSET_MAX, path + ".due", errors);
        return a.withAssignees(ActionSupport.assignees(assigneeStore, entity.emailType(), a.assignees(),
                EmailTargets.offeredRoles(entity.emailType()), path + ".assignees", errors));
    }

    /**
     * Renders the texts, works out the due date and drops the people named who cannot see the record (with a
     * warning each), then works out whom the task would be told to: all read-only, before any lock (PC-4).
     */
    @Override
    public Prepared prepare(CreateTaskAction a, RecordContext rc) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!currentUser.hasIn(type().privilege(), rc.regionId())) problems.add(ActionSupport.notAllowed(type()));
        Map<String, Object> values = new LinkedHashMap<>();
        Rendered title = PlaceholderRenderer.render(a.title(), rc.values(), FieldLimits.TASK_TITLE, true, "Title");
        ActionSupport.collect(title, warnings);
        values.put("title", title.text() == null || title.text().isBlank() ? rc.label() : title.text());
        if (a.description() != null && !a.description().isBlank()) {
            Rendered description = PlaceholderRenderer.render(a.description(), rc.values(),
                    FieldLimits.TASK_DESCRIPTION, false, "Description");
            ActionSupport.collect(description, warnings);
            values.put("description", description.text());
        }
        LocalDate due = a.due() == null ? null : ActionSupport.day(a.due(), rc, warnings);
        values.put("dueDate", due == null ? null : due.toString());
        AssigneeService.Probed probed = assigneeService.probe(AssignedTo.TASK,
                a.assignees() == null ? List.of() : a.assignees(), rc.type().emailType(), rc.id(), rc.regionId());
        warnings.addAll(probed.warnings());
        List<EmailToken> kept;
        try {
            kept = assigneeStore.validate(rc.type().emailType(), probed.kept(),
                    EmailTargets.offeredRoles(rc.type().emailType()));
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            problems.add(e.getFieldErrors().values().iterator().next());
            kept = List.of();
        }
        TaskService.AutomationPlan plan = problems.isEmpty() ? taskService.planForAutomation(
                TaskTargets.of(TaskEntityType.valueOf(rc.type().name()), rc.id(),
                        RecordContexts.label(rc.type(), rc.entity()), rc.customerId(), rc.regionId()),
                rc.target(), kept) : null;
        return new Prepared(values, kept, problems, warnings, plan);
    }

    @Override
    public ActionPreview preview(CreateTaskAction a, RecordContext rc) {
        Prepared p = prepare(a, rc);
        List<String> warnings = new ArrayList<>(p.warnings());
        if (a.onlyIfNoOpenTask() && rc.ruleId() != null && openTaskFromRule(rc)) {
            warnings.add("An open task from this rule is already on this record, so none would be made");
        }
        return new ActionPreview(a.key(), type().name(), p.values(),
                assigneeService.describe(AssignedTo.TASK, p.assignees(), rc.type().emailType(), rc.target()),
                null, p.problems(), warnings);
    }

    /**
     * Makes the task, under the customer's lock taken by the caller: the open-task guard is checked again here,
     * since another run may have made one since {@link #prepare}. The task is made as the rule's owner.
     */
    @Override
    public ActionResult perform(CreateTaskAction a, RecordContext rc, Prepared p) {
        if (!p.problems().isEmpty()) throw new BadRequestException(String.join("; ", p.problems()));
        if (a.onlyIfNoOpenTask() && rc.ruleId() != null && openTaskFromRule(rc)) {
            return new ActionResult.Skipped("An open task from this rule is already on this record");
        }
        Object due = p.values().get("dueDate");
        TaskService.TaskDraft draft = new TaskService.TaskDraft(TaskEntityType.valueOf(rc.type().name()), rc.id(),
                (String) p.values().get("title"), (String) p.values().get("description"),
                due == null ? null : LocalDate.parse((String) due), p.assignees(), rc.ruleId(),
                WriteOrigins.current(currentUser).runId(), true);
        TaskService.Created created = taskService.createForAutomation(draft, (TaskService.AutomationPlan) p.plan());
        List<String> warnings = new ArrayList<>(p.warnings());
        warnings.addAll(created.warnings());
        return new ActionResult.Done(RESULT, created.task().getId(), warnings);
    }

    private boolean openTaskFromRule(RecordContext rc) {
        Long open = em.createQuery("""
                        select count(t) from Task t where t.ruleId = :rule and t.entityType = :type
                           and t.entityId = :id and t.status in :open
                        """, Long.class)
                .setParameter("rule", rc.ruleId())
                .setParameter("type", TaskEntityType.valueOf(rc.type().name()))
                .setParameter("id", rc.id())
                .setParameter("open", List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS))
                .getSingleResult();
        return open > 0;
    }
}
