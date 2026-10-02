package com.geneinvoice.payment;

import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payments", indexes = @Index(name = "idx_payments_region", columnList = "region_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal creditApplied = BigDecimal.ZERO;

    @Column(length = 40)
    private String method;

    @Column(length = 300)
    private String notes;

    @Column(nullable = false)
    private Instant paidAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id")
    private User collectionPoc;

    /** The region this record belongs to: copied from its customer and never written by an ORM update (F3). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", updatable = false)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.ACTIVE;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    @PrePersist
    void onCreate() {
        // The service copies the region from the customer it locked; this only catches a path that forgot.
        if (this.region == null && this.customer != null) this.region = this.customer.getRegion();
        if (this.paidAt == null) {
            this.paidAt = AppClock.now();
        }
    }
}
