package com.geneinvoice.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One region's approval limit. A region with no row of its own is not a region with a limit of
 * zero: it falls back to the deployment default, and a deployment that configures no default
 * holds nothing at all, so switching maker-checker on is an operational act and not a side effect
 * of a release (B2).
 */
@Entity
@Table(name = "approval_thresholds",
        uniqueConstraints = @UniqueConstraint(name = "uk_approval_threshold_region",
                columnNames = "region_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A plain Long and no @ManyToOne, the Dispute.customerId convention: it is what keeps
    // com.geneinvoice.approval from importing a region entity anywhere but RegionLookupImpl (B2, B1).
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    // Switched off is not the same as set very high: it is how a branch says "no maker-checker
    // here" without anybody having to guess which amount means never (B2).
    @Column(nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    @PreUpdate
    void stamp() {
        this.updatedAt = Instant.now();
    }
}
