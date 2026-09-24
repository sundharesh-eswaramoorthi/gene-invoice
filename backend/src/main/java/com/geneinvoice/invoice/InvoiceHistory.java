package com.geneinvoice.invoice;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One interval-versioned version of an {@link Invoice} (B3).
 *
 * <p>THE ATTRIBUTE NAMES AND THE JAVA TYPES ARE THE WHOLE DESIGN: every one is spelled as the live
 * entity spells it, so {@code ScopeResolver.forInvoices}, every {@code ColumnDef} path, the ~34
 * {@code InvoiceService.tiles} selection lambdas and {@code DashboardService}'s expressions all
 * resolve against this root unchanged, and {@code status} is still an enum so {@code ValueCoercion}
 * takes the same branch it takes live (B3).
 *
 * <p>FOREIGN KEYS TO MIRRORED THINGS ARE FLAT LONGS, ON PURPOSE. {@code customerId} is a Long with
 * no {@code Customer} to walk: joining a past invoice to the LIVE account would render today's
 * name, today's terms and today's region on a row that is supposed to be a snapshot, which is
 * exactly the leak as-of exists to close. The POC is the one exception and it is a contract, not
 * an oversight — clause a.3 says the User row is not mirrored and renders as it is today (B3).
 */
@Entity
@Table(name = "invoice_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_invh_open",
                columnNames = {"invoice_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_invh_record", columnList = "invoice_id,valid_from"),
                @Index(name = "idx_invh_window", columnList = "valid_to,valid_from"),
                @Index(name = "idx_invh_cust", columnList = "customer_id,valid_from"),
                @Index(name = "idx_invh_poc", columnList = "sales_poc_user_id,valid_from"),
                @Index(name = "idx_invh_number", columnList = "invoice_number")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceHistory implements HistoryRow, InvoiceView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    // The BUSINESS id, named id and deliberately NOT the primary key, so TableQueryExecutor.ids'
    // cq.select(root.get("id")) and orderBy's tiebreak both mean the invoice and not the version
    // — which is why the mirror's own key is called historyId (B3).
    @Column(name = "invoice_id", nullable = false)
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

    @Column(name = "invoice_number", length = 40)
    private String invoiceNumber;

    // The region axis: Invoice is VIA_CUSTOMER live, but a mirror has no customer association to
    // walk, so the mirror is VIA_CUSTOMER_ID and RegionScope.clause reads this column (B1, B3).
    // Nullable, like every business column on every mirror, and NOT NULL only on the five
    // interval columns above. A TOMBSTONE is a row of this table too: it records that the
    // record is gone, and a gone record has no business values to record. A mirror that
    // insisted on them would turn a hard delete into a failed business transaction, which is
    // the one failure this table must never cause. A column that should have had a value and
    // does not is caught by the reconciler's diff instead (B3).
    @Column(name = "customer_id")
    private Long customerId;

    // Denormalised as-of label: the account's name AS IT WAS THEN. Reading it off the live
    // customer would put today's name on a January invoice (B3).
    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "invoice_date")
    private Instant invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "paid_amount", precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private InvoiceStatus status;

    @Column(length = 500)
    private String notes;

    // The book as of then: ScopeResolver.forInvoices is cb.equal(root.get("salesPocUserId"), me),
    // one lambda that now resolves on a live root and on this one (B3).
    @Column(name = "sales_poc_user_id")
    private Long salesPocUserId;

    @Column(name = "sales_poc_name", length = 120)
    private String salesPocName;

    @Column(name = "created_at")
    private Instant createdAt;

    // The POC's IDENTITY is as-of — that is the column above. This lazy, read-only association
    // exists for two reasons and no others: InvoiceService.tiles:343 does
    // cb.isNull(root.get("salesPoc")), and InvoiceView declares getSalesPoc(). The User row behind
    // it is NOT mirrored, so the name and picture a DTO renders are today's, by contract clause
    // a.3. No foreign key: a mirror row must outlive the person it names (B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User salesPoc;

    // Filled by the slice from region/RegionPlacements before mapping, never persisted: no mirror
    // carries region_id (blueprint conflict 1 struck it), and customer_region_history is the
    // authoritative ledger of which region an account was in on a date. @Transient on purpose —
    // writing to a persisted field of a managed mirror row would be flushed, rewriting history to
    // answer a question (B1, B3).
    @Transient
    private Long regionId;

    @Transient
    private String regionName;
}
