package com.geneinvoice.invoice;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.product.Product;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One interval-versioned version of an {@link InvoiceItem}, so an as-of invoice shows the lines it
 * had THEN and not the lines it has now (B3).
 *
 * <p>{@code product} is a read-only association and the product is NOT mirrored: a line's product
 * NAME renders as it is today, by contract clause a.3, which is the same rule the POC follows.
 * {@code invoiceId} is a flat Long because the invoice IS mirrored and walking to the live row
 * would put today's totals on a past line (B3).
 */
@Entity
@Table(name = "invoice_item_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_iih_open",
                columnNames = {"invoice_item_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_iih_record", columnList = "invoice_item_id,valid_from"),
                @Index(name = "idx_iih_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_iih_invoice", columnList = "invoice_id,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItemHistory implements HistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @Column(name = "invoice_item_id", nullable = false)
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
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "unit_price", precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", precision = 14, scale = 2)
    private BigDecimal lineTotal;

    // Read-only and lazy: the catalogue is not mirrored, so a past line names today's product, by
    // contract clause a.3. No foreign key — a mirror row must outlive the product it names (B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Product product;
}
