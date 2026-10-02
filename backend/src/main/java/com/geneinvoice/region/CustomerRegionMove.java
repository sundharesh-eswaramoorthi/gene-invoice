package com.geneinvoice.region;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One move of a customer, and so of all of its records, from one region to another. Append-only: never changed
 * or deleted, so the moves as of any moment are those made by then. The approval gate reads the recent ones, to
 * keep a customer moved out of a stricter region under that region's thresholds for a while (§5.11.3).
 */
@Entity
@Table(name = "customer_region_moves",
        indexes = @Index(name = "idx_crm_customer_moved", columnList = "customer_id,moved_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerRegionMove {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "from_region_id")
    private Long fromRegionId;

    @Column(name = "to_region_id", nullable = false)
    private Long toRegionId;

    @Column(name = "moved_at", nullable = false, updatable = false)
    private Instant movedAt;

    @Column(name = "moved_by_user_id", updatable = false)
    private Long movedByUserId;
}
