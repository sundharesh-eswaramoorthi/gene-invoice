package com.geneinvoice.poc;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One interval-versioned version of a {@link CustomerPoc} seat: who was on which account, in which
 * role, as of a date (B3).
 *
 * <p>THIS IS WHAT MAKES "MY BOOK AS OF THEN" EXACT. {@code ScopeResolver}'s two seat subqueries
 * ask "does this account have a seat held by me?"; under {@code ?asOf} B3-SCHEMAS re-roots them
 * here, so a person who lost an account in March still sees, as of January, the accounts that were
 * theirs in January. They name {@code customerId} and {@code userId} flat, which is why both are
 * mapped as plain Longs and not walked through an association (B1, B3).
 */
@Entity
@Table(name = "customer_poc_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_cpoch_open",
                columnNames = {"customer_poc_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_cpoch_record", columnList = "customer_poc_id,valid_from"),
                @Index(name = "idx_cpoch_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_cpoch_cust", columnList = "customer_id,poc_type,valid_from"),
                @Index(name = "idx_cpoch_user", columnList = "user_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerPocHistory implements HistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "customer_poc_id", nullable = false)
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

    // Nullable, like every business column on every mirror, and NOT NULL only on the five
    // interval columns above. A TOMBSTONE is a row of this table too: it records that the
    // record is gone, and a gone record has no business values to record. A mirror that
    // insisted on them would turn a hard delete into a failed business transaction, which is
    // the one failure this table must never cause. A column that should have had a value and
    // does not is caught by the reconciler's diff instead (B3).
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "poc_type", length = 20)
    private PocType pocType;

    // Boxed, unlike the live primitive, and named exactly as the live attribute is named. Every
    // business column on a mirror is nullable because a tombstone row records that the seat is
    // GONE and a gone seat has no values (B3).
    @Column(name = "is_primary")
    private Boolean primary;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    // Read-only, lazy, not mirrored behind: the person renders as they are today, by contract
    // clause a.3, and there is no foreign key so the seat's history outlives them (B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;
}
