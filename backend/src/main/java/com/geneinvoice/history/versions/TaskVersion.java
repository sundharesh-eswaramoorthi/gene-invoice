package com.geneinvoice.history.versions;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.region.Region;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A {@code tasks} row over a period. The customer is a plain id, as on the live row. The task's own {@code origin}
 * (who made it) is kept as {@code task_origin}: {@code origin} on a version row is the provenance of that version.
 */
@Entity
@Table(name = "task_versions", indexes = {
        @Index(name = "idx_task_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_task_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_task_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_task_versions_region", columnList = "region_id,valid_to"),
        @Index(name = "idx_task_versions_record", columnList = "entity_type,record_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class TaskVersion extends VersionRow {

    /** The attribute that holds the live {@code origin}. */
    public static final String TASK_ORIGIN = "taskOrigin";

    @Column(length = FieldLimits.TASK_TITLE)
    private String title;

    @Column(length = FieldLimits.TASK_DESCRIPTION)
    private String description;

    @Convert(converter = EnumNames.TaskStatusName.class)
    @Column(length = 20)
    private TaskStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Convert(converter = EnumNames.TaskEntityTypeName.class)
    @Column(name = "entity_type", length = 20)
    private TaskEntityType entityType;

    @Column(name = "record_id")
    private Long entityId;

    @Column(name = "entity_label", length = 200)
    private String entityLabel;

    @Column(name = "entity_deleted")
    private Boolean entityDeleted;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    @Column(name = "task_origin", length = 12)
    private String taskOrigin;

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

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
