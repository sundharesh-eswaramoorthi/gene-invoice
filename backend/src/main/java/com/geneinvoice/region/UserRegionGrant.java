package com.geneinvoice.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * One user's right in one region. "Nothing in a third region" is the ABSENCE of a row: no row
 * ever means NONE, so a missing grant can never be read as a wide one (B1).
 */
@Entity
@Table(name = "user_region_grants", indexes = {
        @Index(name = "idx_grant_user", columnList = "user_id"),
        @Index(name = "idx_grant_region", columnList = "region_id,right_level")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRegionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Null means every region, including ones created after this grant was given — that is what
     * makes a company administrator stay an administrator when a branch opens next year (B1).
     *
     * <p>A plain Long and not a {@code @ManyToOne}, matching Dispute.customerId: the principal
     * outlives the session, and calling getId() on a LAZY proxy of a detached instance is a trap.
     */
    @Column(name = "region_id")
    private Long regionId;

    // Column is right_level, not right: "right" is a reserved word in both Postgres and H2 (B1).
    @Enumerated(EnumType.STRING)
    @Column(name = "right_level", nullable = false, length = 10)
    private RegionRight right;

    // Null for a grant written by the schema upgrade or the seeder, where there is no person (B1).
    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
