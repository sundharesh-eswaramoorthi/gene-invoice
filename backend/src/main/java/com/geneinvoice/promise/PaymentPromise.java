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
public class PaymentPromise implements PromiseView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // The SAME column mapped a second time, read-only, so a book predicate and a ColumnDef can
    // name the id without walking the association. No DDL change — Hibernate writes the column
    // once, through the @ManyToOne above. It is what makes the one lambda compile against
    // PromiseHistory too: a mirror maps its foreign keys as plain Longs (B3).
    @Column(name = "customer_id", insertable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "promised_date", nullable = false)
    private LocalDate promisedDate;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "collection_poc_user_id")
    private User collectionPoc;

    // The same read-only duplicate, for the same reason, over the POC the book is drawn on (B3).
    @Column(name = "collection_poc_user_id", insertable = false, updatable = false)
    private Long collectionPocUserId;

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

    // A promise is rewritten by the sweeper, by a recompute and by a person, so it needs the same
    // lost-update guard the customer and the invoice already carry (B2). Boxed Long, never a
    // primitive: a primitive makes Hibernate emit NOT NULL, and adding a NOT NULL column to a
    // populated table fails under hbm2ddl.halt_on_error (B2).
    @Version
    @Column(name = "version")
    private Long version;

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
     * THE FLAT COLUMN IS A LOAD-TIME MIRROR, SO THE ASSOCIATION ANSWERS THIS AND NOT THE FIELD.
     * Hibernate never refreshes an {@code insertable = false} property after an insert, so on the
     * entity instance the create path just saved — the one every POST answers with —
     * {@code customerId} is still null while the association is right. {@code .getId()} on the
     * lazy proxy initialises nothing, so this costs no query. The mapped field above is untouched
     * and is still what a criteria path names (B3).
     */
    @Override
    public Long getCustomerId() {
        return customer == null ? null : customer.getId();
    }

    /**
     * The same rule over the POC the book is drawn on. collection_poc_user_id is NOT NULL here and
     * the association is optional = false, so the answer is never null on a persisted row — but it
     * is still read off the association, because the duplicate column is null on a promise that has
     * just been created and not re-read (B3).
     */
    public Long getCollectionPocUserId() {
        return collectionPoc == null ? null : collectionPoc.getId();
    }

    // getRemainingAmount() used to live here and now lives as a default on PromiseView, so a live
    // list and an as-of list read ONE definition of how much of a promise is still owed rather
    // than each working it out and eventually disagreeing. The body moved verbatim (B3).

    /**
     * The account's name. It initialises the lazy proxy, which every caller of this already did:
     * the DTO mapper reads the name on the line after the id (B3).
     */
    @Override
    public String getCustomerName() {
        return customer == null ? null : customer.getName();
    }

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403. Null-tolerant on purpose:
     * a DTO built from a half-migrated row must not throw (B1).
     */
    @Override
    public Long getRegionId() {
        return customer == null || customer.getRegion() == null ? null : customer.getRegion().getId();
    }

    @Override
    public String getRegionName() {
        return customer == null || customer.getRegion() == null ? null : customer.getRegion().getName();
    }

    public boolean isTerminal() {
        return status == PromiseStatus.CANCELLED;
    }
}
