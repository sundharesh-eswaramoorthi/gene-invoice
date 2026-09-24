package com.geneinvoice.customer;

import com.geneinvoice.history.HistoryRow;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One interval-versioned version of a {@link Customer} — the account as it stood between
 * {@code validFrom} and {@code validTo} (B3).
 *
 * <p>THE ATTRIBUTE NAMES AND THE JAVA TYPES ARE THE WHOLE DESIGN. Every one of them is spelled
 * exactly as the live entity spells it, so {@code ScopeResolver}'s predicates, every
 * {@code ColumnDef} path, every {@code tiles()} selection lambda and every {@code DashboardService}
 * expression resolves against this root UNCHANGED, and the enum columns still take
 * {@code ValueCoercion}'s {@code target.isEnum()} branch. As-of queryability is therefore the
 * live pipeline with one root swapped, not two thousand lines of re-expression (B3).
 */
@Entity
@Table(name = "customer_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_custh_open",
                columnNames = {"customer_id", "valid_to"}),
        indexes = {
                @Index(name = "idx_custh_record", columnList = "customer_id,valid_from"),
                @Index(name = "idx_custh_window", columnList = "valid_to,valid_from")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerHistory implements HistoryRow, CustomerView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    // The BUSINESS id, named id and deliberately NOT the primary key: TableQueryExecutor.ids does
    // cq.select(root.get("id")) and orderBy appends the same path as its tiebreak, so a list over
    // this root returns and orders by ACCOUNTS and not by versions, with no executor change (B3).
    @Column(name = "customer_id", nullable = false)
    private Long id;

    // The same column a second time, read-only, because the region axis for this mirror is
    // VIA_CUSTOMER_ID and RegionScope.clause names root.get("customerId"). It is VIA_CUSTOMER_ID
    // and not OWN — which the blueprint's table says — because the OWN arm is
    // root.get("region").get("id") and resolving it here would read the region from the MIRROR,
    // losing B3-04's now-clause; RegionScope.java belongs to R4 and B3 does not touch it (B1, B3).
    @Column(name = "customer_id", insertable = false, updatable = false)
    private Long customerId;

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

    @Column(length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(name = "credit_balance", precision = 14, scale = 2)
    private BigDecimal creditBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    // customers.region_id, mirrored because every persisted attribute of the live row must have a
    // mirror column. NOT the region axis: RegionPredicates.asOf reads customer_region_history,
    // which is LocalDate-grained, back-dated to the account's created_at and therefore exact
    // before this mirror's floor — so it, and not this column, answers "which region was this
    // account in then" (B1, B3).
    //
    // No foreign key: a mirror row must outlive the region it names, and the blueprint's only new
    // FK in the whole programme is task_assignees.task_id (B1, B3).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    @Column(name = "created_at")
    private Instant createdAt;

    // Filled by the slice from region/RegionPlacements before mapping, never persisted, so the
    // interface's region pair answers from the authoritative ledger and the DTO factory signature
    // does not grow. @Transient on purpose: writing to a PERSISTED field of a managed mirror row
    // would be dirty-checked and flushed, which would rewrite history to answer a question (B3).
    @Transient
    private Long regionId;

    @Transient
    private String regionName;
}
