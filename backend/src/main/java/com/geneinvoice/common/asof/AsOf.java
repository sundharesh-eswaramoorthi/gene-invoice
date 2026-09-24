package com.geneinvoice.common.asof;

import com.geneinvoice.common.query.PredicateFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The two interval shapes as-of reading is built from, as PredicateFactory so they drop straight
 * into the scope list TableQueryExecutor already ANDs. They live here rather than in
 * com.geneinvoice.history because common.query must never import history and the mirror rows are
 * not the only interval-shaped table in the programme — pending_changes is already one (B3).
 */
public final class AsOf {

    /**
     * THE single definition of the open-interval sentinel. A row that is still current ends at
     * 9999-12-31 and never at NULL, because the uniqueness that makes the writer race-free —
     * "exactly one open row per record" — is a plain unique constraint on (record_id, valid_to),
     * and H2 has no partial indexes to express it any other way. history/HistoryRow.OPEN is an
     * alias of this constant, never a second copy (B3).
     */
    public static final Instant OPEN =
            LocalDate.of(9999, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant();

    private AsOf() {
    }

    /**
     * The one version of a mirrored record that was current at t. Half-open [validFrom, validTo),
     * so exactly one row per record satisfies it and a count over the mirror counts RECORDS and
     * not versions. deleted = false drops the tombstone a hard delete left: the row stays, so the
     * record is still readable as of a date before it went, and is absent after (B3).
     */
    public static PredicateFactory at(Instant t) {
        return (root, query, cb) -> cb.and(
                cb.lessThanOrEqualTo(root.<Instant>get("validFrom"), t),
                cb.greaterThan(root.<Instant>get("validTo"), t),
                cb.isFalse(root.get("deleted")));
    }

    /**
     * Which approvals were outstanding then (B2, B3). pending_changes is already interval-shaped —
     * requestedAt opens it and decidedAt closes it — so "awaiting a second pair of eyes on 31
     * January" needs no mirror table of its own, which is why B2's pending_changes is deliberately
     * NOT in the mirrored set.
     *
     * <p>coalesce onto OPEN rather than an isNull branch: an undecided row and a row decided after
     * t have to answer identically, and one expression keeps the index usable (B3).
     */
    public static PredicateFactory outstandingAt(Instant t) {
        return (root, query, cb) -> cb.and(
                cb.lessThanOrEqualTo(root.<Instant>get("requestedAt"), t),
                cb.greaterThan(cb.coalesce(root.<Instant>get("decidedAt"), OPEN), t));
    }
}
