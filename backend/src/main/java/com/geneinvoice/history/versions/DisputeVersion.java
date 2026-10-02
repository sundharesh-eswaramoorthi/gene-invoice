package com.geneinvoice.history.versions;

import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A {@code disputes} row over a period. The customer is a plain id, as on the live row. The dispute's own
 * {@code origin} (a rule raised it) is kept as {@code dispute_origin}: {@code origin} on a version row is the
 * provenance of that version. {@code internal} is null in a version written before it existed, and reads as false.
 */
@Entity
@Table(name = "dispute_versions", indexes = {
        @Index(name = "idx_dispute_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_dispute_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_dispute_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_dispute_versions_region", columnList = "region_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class DisputeVersion extends VersionRow {

    /** The attribute that holds the live {@code origin}. */
    public static final String DISPUTE_ORIGIN = "disputeOrigin";

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "opened_by_user_id")
    private Long openedByUserId;

    @Convert(converter = EnumNames.DisputeTargetTypeName.class)
    @Column(name = "target_type", length = 20)
    private DisputeTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(length = 2000)
    private String reason;

    @Lob
    @Column(name = "proposed_change_json", columnDefinition = "TEXT")
    private String proposedChangeJson;

    @Convert(converter = EnumNames.DisputeStatusName.class)
    @Column(length = 20)
    private DisputeStatus status;

    @Column(name = "admin_notes", length = 2000)
    private String adminNotes;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    private Boolean internal;

    @Column(name = "dispute_origin", length = 12)
    private String disputeOrigin;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    private Instant createdAt;

    private Instant updatedAt;
}
