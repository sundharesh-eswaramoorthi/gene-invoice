package com.geneinvoice.approval;

import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A region's limit for one operation (§3.8): a change whose amount is above it is held. No row means the
 * operation is not gated in that region.
 */
@Entity
@Table(name = "approval_thresholds", uniqueConstraints =
        @UniqueConstraint(name = "uk_threshold_region_op", columnNames = {"region_id", "operation"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalThreshold {

    public static final int DEFAULT_WINDOW_HOURS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Convert(converter = ApprovalConverters.OperationName.class)
    @Column(nullable = false, length = 40)
    private ApprovalOperation operation;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** The cumulative window (§5.11.3); 0 measures each change alone. */
    @Column(name = "window_hours", nullable = false)
    @Builder.Default
    private int windowHours = DEFAULT_WINDOW_HOURS;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
