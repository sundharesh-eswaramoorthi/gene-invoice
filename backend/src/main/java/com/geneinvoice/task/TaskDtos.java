package com.geneinvoice.task;

import com.geneinvoice.common.FieldLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class TaskDtos {

    /** Who is on a task, and WHY — source is "USER" or a stored RoleRef token (A6, A3). */
    public record AssigneeDto(Long userId, String name, String username, String source) {}

    /**
     * The row shape. The trailing components are fixed by the blueprint so that three features can
     * append without colliding: the existing components, then regionId/regionName (B1). There is
     * deliberately NO approvalPending slot — PendingTargetType has no TASK constant, nothing about
     * a task is threshold-eligible, and leaving the slot free is what stops B2 ever re-entering
     * this file (A6, B1, B2).
     */
    public record TaskDto(
            Long id,
            TaskEntityType entityType,
            Long entityId,
            String entityLabel,
            Long customerId,
            String customerName,
            String title,
            String notes,
            LocalDate dueDate,
            TaskStatus status,
            boolean overdue,
            List<AssigneeDto> assignees,
            Long createdByUserId,
            Long createdByRuleId,
            Long createdByStepId,
            Long completedByUserId,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt,
            Long regionId,
            String regionName) {}

    /**
     * The five tiles, counted inside ONE aggregate over the SAME filters the list ran, so a tile
     * and the rows behind it cannot disagree. No awaitingApprovalCount, for the same reason
     * TaskDto has no approvalPending (A6, B2).
     */
    public record TaskSummaryTiles(long open, long inProgress, long overdue, long dueThisWeek,
                                   long done) {}

    public record CreateTaskRequest(
            @NotNull TaskEntityType entityType,
            @NotNull Long entityId,
            @NotBlank @Size(max = FieldLimits.TASK_TITLE) String title,
            @Size(max = FieldLimits.TASK_NOTES) String notes,
            LocalDate dueDate,
            List<Long> assigneeUserIds) {}

    /**
     * PATCH, so the rule is spelled out once here rather than guessed at every field.
     *
     * <p>title, status and assigneeUserIds are LEFT ALONE when null: a caller changing only the
     * status must not have to resend the title, and a null seat list must not empty the task.
     * notes and dueDate are REPLACED by whatever arrives, null included, because "this no longer
     * has a due date" and "the note is gone" have to be expressible and Jackson cannot tell an
     * absent component from an explicit null (A6).
     *
     * <p>An EMPTY assigneeUserIds list therefore takes everybody off, which is the one way to
     * leave a task assigned to nobody.
     */
    public record UpdateTaskRequest(
            @Size(max = FieldLimits.TASK_TITLE) String title,
            @Size(max = FieldLimits.TASK_NOTES) String notes,
            LocalDate dueDate,
            TaskStatus status,
            List<Long> assigneeUserIds) {}
}
