package com.geneinvoice.dispute;

import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "idx_dispute_customer", columnList = "customer_id"),
        @Index(name = "idx_dispute_target", columnList = "target_type,target_id"),
        @Index(name = "idx_dispute_status", columnList = "status"),
        @Index(name = "idx_disputes_region", columnList = "region_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dispute {

    public static final String ORIGIN_AUTOMATION = "AUTOMATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "opened_by_user_id", nullable = false)
    private Long openedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private DisputeTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Lob
    @Column(name = "proposed_change_json", columnDefinition = "TEXT")
    private String proposedChangeJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DisputeStatus status = DisputeStatus.PENDING;

    @Column(name = "admin_notes", length = 2000)
    private String adminNotes;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Raised by automation for staff to review, never by the customer: a customer login never sees it, nor a change
     * held from it (D17, SC-1). Rows from before it existed are external ones.
     */
    @ColumnDefault("false")
    @Column(nullable = false)
    @Builder.Default
    private boolean internal = false;

    /** {@code AUTOMATION} for an internal dispute a rule raised; null for a customer's own. */
    @Column(length = 12)
    private String origin;

    /** The rule that raised an internal dispute. */
    @Column(name = "rule_id")
    private Long ruleId;

    /**
     * The region of the dispute's customer. The customer is a plain id here, so there is no entity to copy it
     * from in a callback: the service sets it from the customer row it locked.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", updatable = false)
    private Region region;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

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
