package com.geneinvoice.region;

import com.geneinvoice.common.time.AppClock;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Where a record belongs (B1). Every customer, and every invoice, payment, promise and dispute of it, points
 * at exactly one region; exactly one region is the default.
 */
@Entity
@Table(name = "regions", uniqueConstraints = @UniqueConstraint(name = "uk_region_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Region {

    public static final String DEFAULT_CODE = "DEFAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    /**
     * Changed only by RegionService.makeDefault's bulk updates, never by saving a Region: a save composed before
     * a make-default would otherwise write the old flag back and leave no default, or two.
     */
    @Column(name = "is_default", nullable = false, updatable = false)
    private boolean defaultRegion;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = AppClock.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = AppClock.now();
    }

    /** The id of a record's region, without loading it: reading the id of a LAZY proxy does not initialise it. */
    public static Long idOf(Region region) {
        return region == null ? null : region.getId();
    }

    /** For a record's DTO: loads a LAZY region unless the list fetched it with its rows. */
    public static String codeOf(Region region) {
        return region == null ? null : region.getCode();
    }

    public static String nameOf(Region region) {
        return region == null ? null : region.getName();
    }
}
