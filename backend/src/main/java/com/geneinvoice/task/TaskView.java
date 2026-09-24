package com.geneinvoice.task;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Everything {@link TaskService#toDto} reads off a task, in ONE place that both a live
 * {@link Task} and an interval-versioned mirror row can implement (B3).
 *
 * <p>WHY AN INTERFACE AND NOT A SHARED SUPERCLASS: the mirror deliberately maps its foreign keys
 * as plain read-only Longs and is not substitutable at the ORM level, so the sharing is done at
 * the DTO level instead (B3).
 *
 * <p>A task already carries its account as a flat {@code customerId} with no association, so every
 * accessor here resolves on the live entity with no delegate. The assignee seats, the account's
 * name and the account's region are batched by the service and are not read off the row, so they
 * are not declared here (B3, A6).
 */
public interface TaskView {

    Long getId();

    TaskEntityType getEntityType();

    Long getEntityId();

    String getEntityLabel();

    /** The region axis, and never null: a task in no account would be a task in no branch (A6, B1). */
    Long getCustomerId();

    String getTitle();

    String getNotes();

    LocalDate getDueDate();

    TaskStatus getStatus();

    Long getCreatedByUserId();

    Long getCreatedByRuleId();

    Long getCreatedByStepId();

    Long getCompletedByUserId();

    Instant getCompletedAt();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
