package com.geneinvoice.dispute;

import com.geneinvoice.history.HistoryRow;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One interval-versioned version of a {@link Dispute} (B3).
 *
 * <p>A dispute already carries its account as a flat {@code customerId} with no association — the
 * cross-aggregate house convention — so this mirror is the live shape almost line for line, and
 * {@code DisputeView} resolves on it with no delegate at all (B3).
 *
 * <p>{@code proposedChangeJson} is NOT {@code @Lob} here even though the live column is: on
 * Postgres a {@code @Lob} on a text column makes the driver store a large-object OID and the
 * objects leak, which this build has already proven. A plain TEXT column holds the same string
 * and the mirror is written by raw JDBC anyway (B3).
 */
@Entity
@Table(name = "dispute_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_disph_open",
                columnNames = {"dispute_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_disph_record", columnList = "dispute_id,valid_from"),
                @Index(name = "idx_disph_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_disph_cust", columnList = "customer_id,valid_from"),
                @Index(name = "idx_disph_target", columnList = "target_type,target_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisputeHistory implements HistoryRow, DisputeView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "dispute_id", nullable = false)
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

    /** The region axis, exactly as the live row carries it (B1, B3). */
    // Nullable, like every business column on every mirror, and NOT NULL only on the five
    // interval columns above. A TOMBSTONE is a row of this table too: it records that the
    // record is gone, and a gone record has no business values to record. A mirror that
    // insisted on them would turn a hard delete into a failed business transaction, which is
    // the one failure this table must never cause. A column that should have had a value and
    // does not is caught by the reconciler's diff instead (B3).
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "opened_by_user_id")
    private Long openedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    private DisputeTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(length = 2000)
    private String reason;

    @Column(name = "proposed_change_json", columnDefinition = "TEXT")
    private String proposedChangeJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DisputeStatus status;

    @Column(name = "admin_notes", length = 2000)
    private String adminNotes;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
