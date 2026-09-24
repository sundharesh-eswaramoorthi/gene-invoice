package com.geneinvoice.promise;

import com.geneinvoice.history.HistoryRow;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Which payments had been counted against a promise, as of a date: the interval mirror of the
 * {@code payment_promise_payments} join table (B3).
 *
 * <p>Same shape as {@link PromiseInvoiceHistory} and for the same reason: the join table has no
 * entity, so {@code id} is the PROMISE id, the second id is a plain Long, and the open-row
 * constraint is on all three columns (B3).
 */
@Entity
@Table(name = "promise_payment_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_pph_open",
                columnNames = {"promise_id", "payment_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_pph_record", columnList = "promise_id,valid_from"),
                @Index(name = "idx_pph_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_pph_payment", columnList = "payment_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromisePaymentHistory implements HistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    /** The promise: a link is only ever read through the promise that owns it (B3). */
    @Column(name = "promise_id", nullable = false)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

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
}
