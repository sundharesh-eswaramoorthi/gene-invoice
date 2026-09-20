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

/**
 * A customer's commitment to pay a stated amount by a stated date, either in general or against
 * specific invoices. Status is recomputed from payment facts rather than set by hand, except when
 * a collections user explicitly overrides it with a reason.
 */
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

    /** The collections person answerable for this promise. Required. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "collection_poc_user_id")
    private User collectionPoc;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PromiseStatus status = PromiseStatus.OPEN;

    /** How much of the promise the linked payments have settled so far. */
    @Column(name = "fulfilled_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal fulfilledAmount = BigDecimal.ZERO;

    /** When true, auto-evaluation leaves {@link #status} alone (AC-B6 override wins). */
    @Column(name = "status_overridden", nullable = false)
    @Builder.Default
    private boolean statusOverridden = false;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by_user_id")
    private Long overriddenByUserId;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    /** Set when the "promise broke" notification has gone out, so re-evaluation never re-sends it. */
    @Column(name = "broken_notified_at")
    private Instant brokenNotifiedAt;

    /** The invoices this promise covers. Empty means it is a general promise against the account. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "payment_promise_invoices",
            joinColumns = @JoinColumn(name = "promise_id"),
            inverseJoinColumns = @JoinColumn(name = "invoice_id"))
    @Builder.Default
    private Set<Invoice> invoices = new LinkedHashSet<>();

    /** The payments that fulfil it. A payment may fulfil several promises and vice versa. */
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

    /**
     * What is still owed against the promised amount. A promise that is settled or withdrawn owes
     * nothing whatever the arithmetic says: a promise kept because the debt went away elsewhere
     * has a fulfilled amount of zero, and a cancelled one keeps the amount it was raised for, so
     * both used to advertise money still outstanding beside a status saying otherwise (PPD-04).
     */
    public BigDecimal getRemainingAmount() {
        if (status == PromiseStatus.KEPT || status == PromiseStatus.CANCELLED) return BigDecimal.ZERO;
        BigDecimal remaining = amount.subtract(fulfilledAmount == null ? BigDecimal.ZERO : fulfilledAmount);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    public boolean isTerminal() {
        return status == PromiseStatus.CANCELLED;
    }
}
