package com.geneinvoice.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Where a customer belonged, and when. The ledger of record for the region axis: customers.region_id
 * is the fast path for "now", this table answers "as of a date" and is what an as-of read consults.
 * Both are written by RegionCustodyService in one transaction, so they cannot disagree (B1).
 *
 * <p>Half-open intervals [validFrom, validTo), exactly one open row per customer.
 */
@Entity
@Table(name = "customer_region_history", indexes = {
        @Index(name = "idx_crh_customer", columnList = "customer_id,valid_from"),
        @Index(name = "idx_crh_open", columnList = "customer_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerRegionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    /** Inclusive. */
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Exclusive; null is the one open placement. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    // Nullable on purpose: a move made by the schema upgrade, the seeder or an automated path has
    // no person behind it, and a fabricated actor id would be a lie in the audit (B1).
    @Column(name = "moved_by_user_id")
    private Long movedByUserId;

    @Column(length = 300)
    private String reason;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
