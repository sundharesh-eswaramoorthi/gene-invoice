package com.geneinvoice.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A branch or territory. A privilege says WHAT a user may do; a {@link UserRegionGrant} on a
 * region says WHERE; both must hold (B1).
 *
 * <p>No {@code @Version}: a region is edited by hand once in a while and never in the money path,
 * so it stays out of RowVersionUpgrade.TABLES. Customer moves serialise on the customer row (B1).
 */
@Entity
@Table(name = "regions",
        uniqueConstraints = @UniqueConstraint(name = "uk_region_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    // Retiring a region hides it from the pickers without rewriting the customers that live there,
    // and keeps every historical placement readable, which is why nothing ever deletes one (B1).
    @Column(nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean active = true;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
