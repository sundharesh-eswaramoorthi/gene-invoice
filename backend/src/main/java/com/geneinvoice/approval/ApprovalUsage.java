package com.geneinvoice.approval;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One change a maker applied without approval because it was within its limit (§3.8, SC-9). Read by the gate to
 * add up the same maker's recent changes to the same customer, so splitting a change never slips it under the
 * limit. Append-only, not versioned; rows older than the longest window are purged by {@link ApprovalHousekeeping}.
 */
@Entity
@Table(name = "approval_usage",
        indexes = @Index(name = "idx_approval_usage",
                columnList = "maker_user_id,region_id,operation,customer_id,used_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "maker_user_id", nullable = false)
    private Long makerUserId;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Convert(converter = ApprovalConverters.OperationName.class)
    @Column(nullable = false, length = 40)
    private ApprovalOperation operation;

    /** The applied change's own measure. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;
}
