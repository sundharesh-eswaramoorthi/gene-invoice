package com.geneinvoice.payment;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One interval-versioned version of a {@link Payment} (B3).
 *
 * <p>Every attribute is spelled as the live entity spells it, so {@code ScopeResolver.forPayments},
 * every {@code ColumnDef} path and the payment tiles resolve against this root unchanged. The
 * account is a flat Long with no association to walk — joining a past payment to the live customer
 * would render today's name and today's credit balance on a snapshot (B3).
 */
@Entity
@Table(name = "payment_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_payh_open",
                columnNames = {"payment_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_payh_record", columnList = "payment_id,valid_from"),
                @Index(name = "idx_payh_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_payh_cust", columnList = "customer_id,valid_from"),
                @Index(name = "idx_payh_poc", columnList = "collection_poc_user_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentHistory implements HistoryRow, PaymentView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "payment_id", nullable = false)
    private Long id;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    @Builder.Default
    private Instant validTo = HistoryRow.OPEN;

    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean deleted = false;

    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean drifted = false;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    // The region axis: Payment is VIA_CUSTOMER live, and the mirror is VIA_CUSTOMER_ID over this
    // flat column, because there is no customer association to walk (B1, B3).
    // Nullable, like every business column on every mirror, and NOT NULL only on the five
    // interval columns above. A TOMBSTONE is a row of this table too: it records that the
    // record is gone, and a gone record has no business values to record. A mirror that
    // insisted on them would turn a hard delete into a failed business transaction, which is
    // the one failure this table must never cause. A column that should have had a value and
    // does not is caught by the reconciler's diff instead (B3).
    @Column(name = "customer_id")
    private Long customerId;

    /** Denormalised as-of label: the account's name as it was THEN (B3). */
    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "credit_applied", precision = 14, scale = 2)
    private BigDecimal creditApplied;

    @Column(length = 40)
    private String method;

    @Column(length = 300)
    private String notes;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentStatus status;

    // The book as of then: ScopeResolver.forPayments is
    // cb.equal(root.get("collectionPocUserId"), me), one lambda for both roots (B3).
    @Column(name = "collection_poc_user_id")
    private Long collectionPocUserId;

    @Column(name = "collection_poc_name", length = 120)
    private String collectionPocName;

    // Read-only, lazy, and not mirrored behind: PaymentView declares getCollectionPoc() and a
    // tiles aggregate may ask cb.isNull on it. The person renders as they are today, by contract
    // clause a.3, and there is no foreign key so the row outlives them (B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User collectionPoc;

    // Filled by the slice from region/RegionPlacements, never persisted: no mirror carries
    // region_id, and customer_region_history is the authoritative ledger (B1, B3).
    @Transient
    private Long regionId;

    @Transient
    private String regionName;
}
