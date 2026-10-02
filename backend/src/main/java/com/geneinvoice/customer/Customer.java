package com.geneinvoice.customer;

import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "customers", indexes = @Index(name = "idx_customers_region", columnList = "region_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    /**
     * The region this customer and all of its records belong to. Never written by an ORM update, so a stale
     * save can never put an old region back (F3); a move is an explicit operation.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", updatable = false)
    private Region region;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = AppClock.now();
    }
}
