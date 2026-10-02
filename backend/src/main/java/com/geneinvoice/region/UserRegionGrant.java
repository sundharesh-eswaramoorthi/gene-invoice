package com.geneinvoice.region;

import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * A user's access to one region. No row means no access there. Customer logins have none: they take their
 * region from their customer. {@link User} deliberately has no collection of these (the principal is detached).
 */
@Entity
@Table(name = "user_region_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_region", columnNames = {"user_id", "region_id"}),
        indexes = @Index(name = "idx_urg_region", columnList = "region_id,access_level"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRegionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 10)
    private AccessLevel accessLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_scope", nullable = false, length = 10)
    private RecordScope recordScope;

    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @PrePersist
    void onCreate() {
        if (this.grantedAt == null) this.grantedAt = AppClock.now();
    }
}
