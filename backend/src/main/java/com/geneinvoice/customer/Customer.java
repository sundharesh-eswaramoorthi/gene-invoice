package com.geneinvoice.customer;

import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer implements CustomerView {

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

    // Mapped NULLABLE on purpose: ddl-auto:update cannot add a NOT NULL column to a populated
    // table and halt_on_error:true then refuses to start, so the not-null lives in
    // RegionSchemaUpgrade — exactly as Invoice.dueDate does today (B1, D-01).
    //
    // The setter is package-private so RegionCustodyService, which writes customers.region_id and
    // the customer_region_history row in one transaction, stays the only thing that can move an
    // account. Anything that could set this field alone could make the two disagree (B1).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    @Setter(AccessLevel.PACKAGE)
    private Region region;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403. A customer's region is
     * its own — this is the only region column there is (B1).
     *
     * <p>Null-tolerant on purpose: a DTO built from a half-migrated row must not throw. It is a
     * READ, so it does not reopen the package-private setter above — nothing here can make
     * customers.region_id and customer_region_history disagree (B1, B3).
     */
    @Override
    public Long getRegionId() {
        return region == null ? null : region.getId();
    }

    @Override
    public String getRegionName() {
        return region == null ? null : region.getName();
    }
}
