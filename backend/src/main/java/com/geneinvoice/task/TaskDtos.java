package com.geneinvoice.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.email.EmailDtos.EmailToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class TaskDtos {

    public record CreateTaskRequest(
            @NotNull TaskEntityType entityType,
            @NotNull Long entityId,
            @NotBlank @Size(max = FieldLimits.TASK_TITLE) String title,
            @Size(max = FieldLimits.TASK_DESCRIPTION) String description,
            LocalDate dueDate,
            List<EmailToken> assignees
    ) {}

    /** Only what is sent changes; {@code clearDueDate} removes the due date; {@code assignees} replaces them all. */
    public record PatchTaskRequest(
            @Size(max = FieldLimits.TASK_TITLE) String title,
            @Size(max = FieldLimits.TASK_DESCRIPTION) String description,
            LocalDate dueDate,
            Boolean clearDueDate,
            List<EmailToken> assignees,
            @NotNull Long version
    ) {}

    public record StatusRequest(@NotNull TaskStatus status, @NotNull Long version) {}

    public record Ref(Long id, String name) {}

    /** In historical mode {@code version} is null and both flags false (D14). */
    public record TaskDto(
            Long id,
            String title,
            String description,
            TaskStatus status,
            LocalDate dueDate,
            boolean overdue,
            TaskEntityType entityType,
            Long entityId,
            String entityLabel,
            boolean entityDeleted,
            Long customerId,
            String customerName,
            Long regionId,
            String regionCode,
            String regionName,
            List<AssigneeDtos.AssigneeDto> assignees,
            boolean assignedToMe,
            String origin,
            @JsonInclude(JsonInclude.Include.NON_NULL) Ref rule,
            @JsonInclude(JsonInclude.Include.NON_NULL) Ref createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long version,
            boolean canEdit,
            boolean canChangeStatus
    ) {}

    /** The count, and how many are open, in progress, overdue and due today. */
    public record TaskSummaryDto(long count, long open, long inProgress, long overdue, long dueToday) {}

    /** A record tab's badge: every task on it, and those still to be done. */
    public record TaskCountDto(long count, long open) {}

    public record MineCountDto(long count) {}

    /** What an audit row keeps of a task: its own fields, never a caller's rights or its assignees' names. */
    public record TaskAuditSnapshot(Long id, String title, String description, TaskStatus status, LocalDate dueDate,
                                    TaskEntityType entityType, Long entityId, String entityLabel,
                                    boolean entityDeleted, Long customerId, Long regionId, String origin,
                                    Long ruleId, Instant completedAt, Long completedByUserId, Instant cancelledAt) {}
}
