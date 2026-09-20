package com.geneinvoice.user;

import com.geneinvoice.role.Role;
import jakarta.persistence.*;
import lombok.*;

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

    /**
     * When this account's password last changed. Tokens minted before it are refused, so changing
     * a password ends the sessions somebody else may have taken — the one thing a person can do
     * about a stolen session, which used to leave the thief a working token for the rest of its
     * 24 hours (AUTH-04). Null on an account whose password has not changed since the column was
     * added, and a null lets every token through, so the upgrade signs nobody out.
     */
    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
