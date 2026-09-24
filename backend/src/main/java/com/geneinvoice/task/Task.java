package com.geneinvoice.task;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A piece of work somebody owes on a customer, an invoice or a payment (A6).
 *
 * <p>The polymorphic shape is entityType + entityId + entityLabel + customerId, exactly as
 * Document and Email carry it: a raw kind and id with no association, a LABEL snapshotted at
 * creation so a list can name the record without touching three tables, and the account the
 * record belongs to denormalised onto the row.
 *
 * <p>customer_id is NOT NULL and it is the region axis. The blueprint strikes Part A's original
 * tasks.region_id: region is anchored on customers and nowhere else, so a task's branch is its
 * customer's branch, and a customer that moves takes its tasks with it with no row to rewrite
 * (A6, B1).
 *
 * <p>There is deliberately no @Version. A task is not money, nothing about it is
 * threshold-eligible, and PendingTargetType has no TASK constant — so there is no held change to
 * compare a row version against (A6, B2).
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_entity", columnList = "entity_type,entity_id,status"),
        @Index(name = "idx_task_customer", columnList = "customer_id"),
        @Index(name = "idx_task_due", columnList = "status,due_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task implements TaskView {

    /** The snapshot label's ceiling, the Document.LABEL_MAX shape and the same 200 (A6). */
    public static final int LABEL_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private TaskEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_label", nullable = false, length = LABEL_MAX)
    private String entityLabel;

    // The region axis, so it is NOT NULL: a task in no account would be a task in no branch, and
    // a region-scoped read would silently skip it rather than refuse it (A6, B1).
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, length = FieldLimits.TASK_TITLE)
    private String title;

    @Column(length = FieldLimits.TASK_NOTES)
    private String notes;

    @Column(name = "due_date")
    private LocalDate dueDate;

    // @ColumnDefault so that a column added to a by-then-populated table cannot repeat the D-01
    // failure, and @Builder.Default so every insert path sets it anyway (A6).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'OPEN'")
    @Builder.Default
    private TaskStatus status = TaskStatus.OPEN;

    // Never null in practice: an automated task is stamped with the RULE'S AUTHOR, who is a real
    // accountable person, because a null or synthetic actor voids maker-checker (A6, B2).
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    // Provenance, null when a person made it by hand. The pair is what lets the detail screen say
    // "created by rule X" and what stops A-CONSUMER creating the same task twice (A6, A5).
    @Column(name = "created_by_rule_id")
    private Long createdByRuleId;

    @Column(name = "created_by_step_id")
    private Long createdByStepId;

    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
