package com.geneinvoice.payment;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payments")
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

    /**
     * Who collected this payment. Mandatory on create; stays null on payments that predate the
     * field, which surface a "POC missing" badge instead of being blocked.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id")
    private User collectionPoc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.ACTIVE;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (this.paidAt == null) {
            this.paidAt = Instant.now();
        }
    }
}
