package com.geneinvoice.task;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Something to be done about a customer, an invoice or a payment (A6), by the people and roles in its assignees
 * ({@code record_assignees}, owner type TASK). Versioned for as-of reads like the records it is on (D8).
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_task_customer", columnList = "customer_id"),
        @Index(name = "idx_task_status_due", columnList = "status,due_date"),
        @Index(name = "idx_task_rule", columnList = "rule_id,entity_type,entity_id,status"),
        @Index(name = "idx_task_region", columnList = "region_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    public static final String ORIGIN_USER = "USER";
    public static final String ORIGIN_AUTOMATION = "AUTOMATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = FieldLimits.TASK_TITLE)
    private String title;

    @Column(length = FieldLimits.TASK_DESCRIPTION)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private TaskEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** What the record was called when the task was made; kept once the record is gone. */
    @Column(name = "entity_label", nullable = false, length = 200)
    private String entityLabel;

    @Column(name = "entity_deleted", nullable = false)
    @Builder.Default
    private boolean entityDeleted = false;

    /** The customer of the record, a plain id as on a dispute: a task outlives a deleted customer. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Copied from the customer row locked in the same transaction (F1); moved only with the customer. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", updatable = false)
    private Region region;

    /** USER or AUTOMATION: who made the task, not who last changed it. */
    @Column(nullable = false, length = 12)
    private String origin;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by_user_id")
    private Long completedByUserId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = AppClock.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = AppClock.now();
    }
}
