package com.geneinvoice.user;

import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

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

    /**
     * Where this person may work. EAGER because it is read on every single request through the
     * principal, exactly as the role's privileges already are; a separate repository lookup would
     * cost a query per request and a second seam in the test base. @BatchSize is a new idiom in
     * this codebase and it is here because GET /api/users pages 20-50 rows: without it an EAGER
     * collection is one extra select per user, with it the whole page costs one (B1).
     */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "user_id")
    @BatchSize(size = 50)
    @Builder.Default
    private Set<UserRegionGrant> regionGrants = new HashSet<>();

    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
