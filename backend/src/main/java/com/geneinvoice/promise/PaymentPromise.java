package com.geneinvoice.promise;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "payment_promises", indexes = {
        @Index(name = "idx_promise_customer", columnList = "customer_id"),
        @Index(name = "idx_promise_status", columnList = "status"),
        @Index(name = "idx_promise_date", columnList = "promised_date"),
        @Index(name = "idx_promise_poc", columnList = "collection_poc_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentPromise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "promised_date", nullable = false)
    private LocalDate promisedDate;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "collection_poc_user_id")
    private User collectionPoc;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PromiseStatus status = PromiseStatus.OPEN;

    @Column(name = "fulfilled_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal fulfilledAmount = BigDecimal.ZERO;

    @Column(name = "status_overridden", nullable = false)
    @Builder.Default
    private boolean statusOverridden = false;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by_user_id")
    private Long overriddenByUserId;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @Column(name = "broken_notified_at")
    private Instant brokenNotifiedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "payment_promise_invoices",
            joinColumns = @JoinColumn(name = "promise_id"),
            inverseJoinColumns = @JoinColumn(name = "invoice_id"))
    @Builder.Default
    private Set<Invoice> invoices = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "payment_promise_payments",
            joinColumns = @JoinColumn(name = "promise_id"),
            inverseJoinColumns = @JoinColumn(name = "payment_id"))
    @Builder.Default
    private Set<Payment> payments = new LinkedHashSet<>();

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public BigDecimal getRemainingAmount() {
        if (status == PromiseStatus.KEPT || status == PromiseStatus.CANCELLED) return BigDecimal.ZERO;
        BigDecimal remaining = amount.subtract(fulfilledAmount == null ? BigDecimal.ZERO : fulfilledAmount);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    public boolean isTerminal() {
        return status == PromiseStatus.CANCELLED;
    }
}
