package com.geneinvoice.approval;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.TableSchema;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.util.Arrays;
import java.util.List;

/**
 * The queue as a table, plus the ONE resolver that answers "does this record have a change
 * waiting?" for every other table in the application.
 *
 * <p>It lives here rather than in TableSchemas because a feature that publishes a new table owns
 * its own schema: registering it is one line in SchemaRegistry and not another edit to the file
 * every other feature is also queueing behind (B2, B1 INTEGRATION).
 *
 * <p>{@link #openPending} and {@link #existsOpenPending} live here rather than beside the four
 * ColumnDefs that call them for a reason that is entirely about B3: making the queue as-of aware
 * is a change to one predicate — read AsOfContext and swap {@code status = PENDING} for
 * AsOf.outstandingAt(T) — and a twin of this shape inside TableSchemas would be a second place to
 * forget (B2, B3 INTEGRATION).
 */
public final class ApprovalSchemas {

    private ApprovalSchemas() {}

    private static List<String> names(Class<? extends Enum<?>> e) {
        return Arrays.stream(e.getEnumConstants()).map(Enum::name).toList();
    }

    /**
     * PendingChange is classified OWN_ID, so TableQueryExecutor ANDs in the caller's own regions
     * at VIEW level before this list has said anything: the queue is narrowed to the branches the
     * caller works in by the axis and not by a scope argument anybody could forget (B2, B1).
     */
    public static final TableSchema APPROVALS = TableSchema.of(
            "approvals", PendingChange.class, "requestedAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).build(),
            ColumnDef.of("action", "Action", ColumnType.ENUM)
                    .enumValues(names(PendingAction.class)).build(),
            ColumnDef.of("targetType", "Record type", ColumnType.ENUM)
                    .enumValues(names(PendingTargetType.class)).build(),
            ColumnDef.of("targetId", "Record id", ColumnType.NUMBER).build(),
            ColumnDef.of("customerId", "Customer", ColumnType.REFERENCE)
                    .reference("customer").build(),
            // The branch the change was raised in, frozen at raise time. notSortable because
            // ordering a queue by branch id tells nobody anything and the chip already says
            // which branches are in the list at all (B2, B1).
            ColumnDef.of("regionId", "Region", ColumnType.REFERENCE)
                    .reference("region").pocRestricted().notSortable().build(),
            ColumnDef.of("summary", "Summary", ColumnType.TEXT).build(),
            ColumnDef.of("exposure", "Amount", ColumnType.MONEY).build(),
            ColumnDef.of("thresholdApplied", "Limit applied", ColumnType.MONEY).build(),
            ColumnDef.of("alwaysChecked", "Always checked", ColumnType.BOOLEAN).build(),
            ColumnDef.of("status", "Status", ColumnType.ENUM)
                    .enumValues(names(PendingChangeStatus.class)).build(),
            ColumnDef.of("requestedByUserId", "Raised by", ColumnType.REFERENCE)
                    .reference("user").build(),
            ColumnDef.of("requestedAt", "Raised", ColumnType.DATE).build(),
            ColumnDef.of("decidedByUserId", "Decided by", ColumnType.REFERENCE)
                    .reference("user").build(),
            ColumnDef.of("decidedAt", "Decided", ColumnType.DATE).build(),
            // One bulk run's changes share a batch id. notSortable: it is an opaque handle for
            // "decide all of these in one act" and not an order anybody reads (B2).
            ColumnDef.of("batchId", "Batch", ColumnType.TEXT).notSortable().build());

    /**
     * The filter behind {@code ?filter=approvalPending:eq:true} on invoices, payments, customers
     * and promises. BOOLEAN supports EQ only, so false is the negation of the same EXISTS rather
     * than a second shape that could drift from it (B2).
     */
    public static Predicate openPending(FilterSpec spec, From<?, ?> root, CriteriaQuery<?> q,
                                        CriteriaBuilder cb, PendingTargetType type) {
        Predicate open = existsOpenPending(root, q, cb, type);
        return asBoolean(spec.first()) ? open : cb.not(open);
    }

    /**
     * The correlated EXISTS itself, shared by the four filters and the four tiles so that the
     * count on a tile and the rows behind the filter chip can never disagree (B2).
     *
     * <p>Correlated on the ROOT's id rather than on a join, because pending_changes keeps a raw
     * {@code target_id} Long with no association to walk — the Dispute.customerId house
     * convention for a cross-aggregate reference (B2).
     */
    public static Predicate existsOpenPending(From<?, ?> root, CriteriaQuery<?> q,
                                              CriteriaBuilder cb, PendingTargetType type) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<PendingChange> pc = sq.from(PendingChange.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(pc.get("targetType"), type),
                cb.equal(pc.get("targetId"), root.get("id")),
                waiting(pc, q, cb));
        return cb.exists(sq);
    }

    /**
     * "A change was waiting on this record" — today's answer, and the answer as of a date, from
     * ONE resolver rather than from an as-of twin of the column (blueprint conflict 85).
     *
     * <p>pending_changes needs no mirror table at all and deliberately has none: it is append-only
     * and already interval-shaped, because requestedAt opens the wait and every terminal
     * transition sets decidedAt to close it. So the as-of arm is B3's AsOf.outstandingAt(T) over
     * the live table, which is the cheapest correct answer in the design and the reason
     * PendingChange is absent from HistoryRegistry (B2, B3).
     *
     * <p>THE LIVE ARM IS UNCHANGED AND IS NOT THE INTERVAL AT NOW. PENDING and not "not decided":
     * SUPERSEDED and WITHDRAWN are terminal too, and a record whose change was taken back is not a
     * record with a change waiting. The two arms agree only because that rider holds; keeping the
     * status test on the live path means a row that ever broke it changes no live answer (B2, B3).
     */
    private static Predicate waiting(Root<PendingChange> pc, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return AsOfContext.isActive()
                ? AsOf.outstandingAt(AsOfContext.instant()).build(pc, q, cb)
                : cb.equal(pc.get("status"), PendingChangeStatus.PENDING);
    }

    // The same wording TableSchemas.overdue answers a malformed boolean with, because a caller
    // typing approvalPending:eq:yes must not be able to tell which column refused them (B2).
    private static boolean asBoolean(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new BadRequestException("Expected true or false but got: " + raw);
    }
}
