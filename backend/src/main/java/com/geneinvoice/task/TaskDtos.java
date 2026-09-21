package com.geneinvoice.task;

import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.email.EmailDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class TaskDtos {

    /**
     * Raising a task. The record is named by kind and id, the same pair the email and document
     * forms take, and {@code assignees} carries the very tokens the recipient picker produces, so
     * one widget serves both (A1). A status is accepted on creation because an automation rule may
     * raise work that is already in progress; left out, it is {@link TaskStatus#OPEN}.
     */
    public record CreateTaskRequest(
            @NotBlank String entityType,
            @NotNull Long entityId,
            @NotBlank @Size(max = Task.TITLE_MAX) String title,
            LocalDate dueDate,
            String status,
            @Size(max = Task.NOTES_MAX) String notes,
            List<EmailDtos.EmailToken> assignees) {}

    /**
     * Editing one. The record it hangs off is not among the fields: moving a task to another
     * customer's invoice would take its assignees, its audit trail and its customer scope with it,
     * so a task raised on the wrong record is deleted and raised again (T8).
     *
     * <p>A null {@code title} or {@code status} leaves that field alone, because a task must
     * always have both. A null {@code dueDate} or {@code notes} clears it — taking a due date off
     * a task is an everyday edit and a JSON body cannot tell "absent" from "null", so the form
     * sends the whole thing. A null {@code assignees} leaves the list as it is, while an empty one
     * clears it: a patch that only moves the status must never silently unassign everybody.
     */
    public record UpdateTaskRequest(
            @Size(max = Task.TITLE_MAX) String title,
            LocalDate dueDate,
            String status,
            @Size(max = Task.NOTES_MAX) String notes,
            List<EmailDtos.EmailToken> assignees) {}

    /**
     * One task as the caller may see it. {@code entityLabel} and {@code entityLink} are what the
     * record is called and where it opens, so a "my tasks" list needs nothing else to render a row.
     * {@code overdue} is worked out on the way out rather than stored, because it changes at
     * midnight with nobody touching the row (T9).
     */
    public record TaskDto(
            Long id,
            String entityType,
            Long entityId,
            String entityLabel,
            String entityLink,
            Long customerId,
            String title,
            LocalDate dueDate,
            TaskStatus status,
            String statusLabel,
            boolean overdue,
            String notes,
            List<AssigneeDtos.AssigneeDto> assignees,
            Long createdByUserId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}
}
