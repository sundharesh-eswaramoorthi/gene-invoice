package com.geneinvoice.promise;

import com.geneinvoice.history.HistoryRow;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Which invoices a promise covered, as of a date: the interval mirror of the
 * {@code payment_promise_invoices} join table (B3).
 *
 * <p>The join table has NO entity, so nothing Hibernate calls an insert or an update ever happens
 * to it — the three collection events are what covers it, and they yield the owning promise's id.
 * A link therefore has no business id of its own: {@code id} is the PROMISE id, the second id is a
 * plain Long, and the open-row constraint is on all three columns (B3).
 */
@Entity
@Table(name = "promise_invoice_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_pih_open",
                columnNames = {"promise_id", "invoice_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_pih_record", columnList = "promise_id,valid_from"),
                @Index(name = "idx_pih_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_pih_invoice", columnList = "invoice_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromiseInvoiceHistory implements HistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    /** The promise: a link is only ever read through the promise that owns it (B3). */
    @Column(name = "promise_id", nullable = false)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

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
