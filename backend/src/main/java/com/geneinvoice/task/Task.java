package com.geneinvoice.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One piece of work somebody owes on one record (T1): call this customer, chase this invoice,
 * check this payment. The record is referenced by kind and id rather than by an association, as a
 * document and an email are, so one table answers for all three kinds and nothing has to be added
 * to customers, invoices or payments to carry their tasks.
 *
 * <p>Who it is for is not here: assignees live in the shared {@code assignees} table, because a
 * task may be given to several people or to a role rather than a person (A1).
 *
 * <p>{@link #customerId} and {@link #entityLabel} are copies, not joins. The customer is what the
 * list scopes and the customer-delete cascade sweeps on, and the label is what the record was
 * called when the task was raised — so a page of tasks across customers, invoices and payments is
 * one query with no polymorphic join, exactly as {@code Document} does it (T3).
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_task_customer", columnList = "customer_id"),
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_due", columnList = "due_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    public static final int TITLE_MAX = 200;
    public static final int LABEL_MAX = 200;
    public static final int NOTES_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private TaskEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** The record's customer, so a list scopes and the delete cascade sweeps without joining. */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    /** What the record was called when the task was raised, e.g. "Invoice INV-1" — a snapshot, not a join. */
    @Column(name = "entity_label", length = LABEL_MAX)
    private String entityLabel;

    /** Optional: plenty of work is "when you get to it", and a date nobody meant is worse than none. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.OPEN;

    @Column(length = NOTES_MAX)
    private String notes;

    /** Null for a task an automation rule raised: no one person asked for it (T7). */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    /**
     * When it reached a terminal status, kept beside {@link #updatedAt} because editing the notes
     * of a finished task must not look like finishing it again (T4).
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
