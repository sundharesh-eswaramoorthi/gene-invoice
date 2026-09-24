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
public class Payment implements PaymentView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // The SAME column mapped a second time, read-only, so a book predicate and a ColumnDef can
    // name the id without walking the association. No DDL change — Hibernate writes the column
    // once, through the @ManyToOne above. It is what makes the one lambda compile against
    // PaymentHistory too: a mirror maps its foreign keys as plain Longs (B3).
    @Column(name = "customer_id", insertable = false, updatable = false)
    private Long customerId;

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

    // The same read-only duplicate, for the same reason, over the POC the book is drawn on (B3).
    @Column(name = "collection_poc_user_id", insertable = false, updatable = false)
    private Long collectionPocUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.ACTIVE;

    // A payment is edited and voided from several screens at once, and without a counter the
    // second write simply overwrote the first in silence; with it the loser is told (B2).
    // Boxed Long, never a primitive: a primitive makes Hibernate emit NOT NULL, and adding a
    // NOT NULL column to a populated table fails under hbm2ddl.halt_on_error (B2).
    @Version
    @Column(name = "version")
    private Long version;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (this.paidAt == null) {
            this.paidAt = Instant.now();
        }
    }

    /**
     * THE FLAT COLUMN IS A LOAD-TIME MIRROR, SO THE ASSOCIATION ANSWERS THIS AND NOT THE FIELD.
     * Hibernate never refreshes an {@code insertable = false} property after an insert, so on the
     * entity instance {@code record()} just saved — the one every POST answers with —
     * {@code customerId} is still null while the association is right. {@code .getId()} on the
     * lazy proxy initialises nothing, so this costs no query. The mapped field above is untouched
     * and is still what a criteria path names (B3).
     */
    @Override
    public Long getCustomerId() {
        return customer == null ? null : customer.getId();
    }

    /**
     * The same rule over the POC the book is drawn on, and here the association is the ONLY honest
     * answer: taking the person off with {@code setCollectionPoc(null)} leaves the duplicate column
     * holding the id they used to have until the row is read again (B3).
     */
    public Long getCollectionPocUserId() {
        return collectionPoc == null ? null : collectionPoc.getId();
    }

    /**
     * The account's name. It initialises the lazy proxy, which every caller of this already did:
     * the DTO factory reads the name on the line after the id (B3).
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
}
