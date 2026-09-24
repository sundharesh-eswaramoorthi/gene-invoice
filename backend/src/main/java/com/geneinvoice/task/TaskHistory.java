package com.geneinvoice.task;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.history.HistoryRow;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One interval-versioned version of a {@link Task} (A6, B3).
 *
 * <p>A task already carries its account as a flat {@code customerId} and its subject as
 * entityType + entityId + a snapshotted label, so this mirror is the live shape line for line and
 * {@code TaskView} resolves on it with no delegate. The assignee seats are NOT mirrored: a seat is
 * reached only through its task, and A6 gave it no list of its own (A6, B3).
 */
@Entity
@Table(name = "task_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_taskh_open",
                columnNames = {"task_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_taskh_record", columnList = "task_id,valid_from"),
                @Index(name = "idx_taskh_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_taskh_cust", columnList = "customer_id,valid_from"),
                @Index(name = "idx_taskh_entity", columnList = "entity_type,entity_id,status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskHistory implements HistoryRow, TaskView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "task_id", nullable = false)
    private Long id;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    @Builder.Default
    private Instant validTo = HistoryRow.OPEN;

    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean deleted = false;

    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean drifted = false;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", length = 20)
    private TaskEntityType entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "entity_label", length = Task.LABEL_MAX)
    private String entityLabel;

    /** The region axis, exactly as the live row carries it (A6, B1, B3). */
    // Nullable, like every business column on every mirror, and NOT NULL only on the five
    // interval columns above. A TOMBSTONE is a row of this table too: it records that the
    // record is gone, and a gone record has no business values to record. A mirror that
    // insisted on them would turn a hard delete into a failed business transaction, which is
    // the one failure this table must never cause. A column that should have had a value and
    // does not is caught by the reconciler's diff instead (B3).
    @Column(name = "customer_id")
    private Long customerId;

    @Column(length = FieldLimits.TASK_TITLE)
    private String title;

    @Column(length = FieldLimits.TASK_NOTES)
    private String notes;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStatus status;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_rule_id")
    private Long createdByRuleId;

    @Column(name = "created_by_step_id")
    private Long createdByStepId;

    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
