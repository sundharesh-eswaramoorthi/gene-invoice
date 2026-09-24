package com.geneinvoice.payment;

import com.geneinvoice.history.HistoryRow;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One interval-versioned version of a {@link PaymentAllocation}: which invoice a payment was put
 * against, and for how much, as of a date (B3).
 *
 * <p>IT DENORMALISES MORE THAN IT MIRRORS, AND THAT IS THE POINT. {@code paymentStatus},
 * {@code paidAt}, {@code customerId} and {@code customerName} are copied onto the row so that
 * B3-DASHBOARD's collected-as-of figure — which live is a three-join walk from the allocation to
 * the payment to the invoice to the customer — reads them FLAT, with no joins at all and no risk
 * of joining a past allocation to today's payment (B3).
 */
@Entity
@Table(name = "payment_allocation_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_pah_open",
                columnNames = {"allocation_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_pah_record", columnList = "allocation_id,valid_from"),
                @Index(name = "idx_pah_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_pah_payment", columnList = "payment_id,valid_from"),
                @Index(name = "idx_pah_invoice", columnList = "invoice_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationHistory implements HistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "allocation_id", nullable = false)
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
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    /** Denormalised from the payment, so the money figure needs no join (B3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", length = 150)
    private String customerName;
}
