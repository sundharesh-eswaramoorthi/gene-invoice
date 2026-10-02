package com.geneinvoice.user;

import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.role.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 80)
    private String username;

    @Column(unique = true, length = 120)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(length = 120)
    private String fullName;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    /**
     * On a customer login: the staff member who last set its password. That person could sign in as the
     * customer, so their approval of the customer's own dispute proposal is not the second pair of eyes
     * (§5.11.8, SC-10). A change of password made while signed in as the customer keeps it: whoever knew the old
     * password may be the one who made it. Null on a login from before this column; see
     * {@code DisputeService.credentialsSetBy}.
     */
    @Column(name = "credentials_set_by_user_id")
    private Long credentialsSetByUserId;

    /**
     * True only on accounts that existed before regions: the schema default fills it for them, while every
     * account saved since writes false (F5). RegionSchemaUpgrade grants those the default region once, then
     * clears it, so an account later left without grants on purpose is never granted again.
     */
    @ColumnDefault("true")
    @Column(name = "region_backfill_pending", nullable = false)
    private boolean regionBackfillPending;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = AppClock.now();
    }
}
