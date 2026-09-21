package com.geneinvoice.poc;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "customer_pocs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_poc", columnNames = {"customer_id", "user_id", "poc_type"}),
        indexes = {
                @Index(name = "idx_customer_poc_customer", columnList = "customer_id,poc_type"),
                @Index(name = "idx_customer_poc_user", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "poc_type", nullable = false, length = 20)
    private PocType pocType;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean primary = false;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
