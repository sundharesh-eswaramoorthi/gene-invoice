package com.geneinvoice.promise;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One interval-versioned version of a {@link PaymentPromise} (B3).
 *
 * <p>Every attribute is spelled as the live entity spells it, so {@code ScopeResolver.forPromises},
 * every {@code ColumnDef} path and {@code PromiseView.getRemainingAmount}'s one definition of how
 * much is still owed all work against this root unchanged. The two link collections are NOT here:
 * they are their own mirrors, because a promise's invoices and payments change independently of
 * the promise row and a Set on this class could only ever answer with today's (B3).
 */
@Entity
@Table(name = "promise_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_promh_open",
                columnNames = {"promise_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_promh_record", columnList = "promise_id,valid_from"),
                @Index(name = "idx_promh_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_promh_cust", columnList = "customer_id,valid_from"),
                @Index(name = "idx_promh_poc", columnList = "collection_poc_user_id,valid_from"),
                @Index(name = "idx_promh_date", columnList = "promised_date"),
                @Index(name = "idx_promh_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromiseHistory implements HistoryRow, PromiseView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "promise_id", nullable = false)
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

    // The region axis: PaymentPromise is VIA_CUSTOMER live and the mirror is VIA_CUSTOMER_ID over
    // this flat column, because there is no customer association to walk (B1, B3).
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

    @Column(name = "promised_date")
    private LocalDate promisedDate;

    // The book as of then: ScopeResolver.forPromises is
    // cb.equal(root.get("collectionPocUserId"), me), one lambda for both roots (B3).
    @Column(name = "collection_poc_user_id")
    private Long collectionPocUserId;

    @Column(name = "collection_poc_name", length = 120)
    private String collectionPocName;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PromiseStatus status;

    @Column(name = "fulfilled_amount", precision = 14, scale = 2)
    private BigDecimal fulfilledAmount;

    // Boxed, unlike the live primitive, and named EXACTLY as the live attribute is named so a
    // ColumnDef path resolves on both roots. Every business column on a mirror is nullable for
    // one reason: a tombstone row records that the record is GONE, and a gone record has no
    // business values to record (B3).
    @Column(name = "status_overridden")
    private Boolean statusOverridden;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by_user_id")
    private Long overriddenByUserId;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @Column(name = "broken_notified_at")
    private Instant brokenNotifiedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Read-only, lazy, not mirrored behind: PromiseView declares getCollectionPoc(). The person
    // renders as they are today, by contract clause a.3, and there is no foreign key so the row
    // outlives them (B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User collectionPoc;

    // Filled by the slice from region/RegionPlacements, never persisted (B1, B3).
    @Transient
    private Long regionId;

    @Transient
    private String regionName;

    /**
     * {@code PromiseView.isStatusOverridden()} is a primitive, and a tombstone row has no business
     * values at all, so the column is boxed and this reads null as "not overridden" — the only
     * honest answer for a version of a record that no longer exists (B3).
     */
    @Override
    public boolean isStatusOverridden() {
        return Boolean.TRUE.equals(statusOverridden);
    }
}
