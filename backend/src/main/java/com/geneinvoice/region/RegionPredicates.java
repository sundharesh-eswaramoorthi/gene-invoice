package com.geneinvoice.region;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * The five Criteria shapes the region axis needs that are not a one-hop path compare. They live
 * apart from RegionScope so the shape can be read, reviewed and reused without reading the policy
 * that decides when to apply it (B1).
 */
public final class RegionPredicates {

    private RegionPredicates() {
    }

    /**
     * For a row that stores a bare {@code customer_id} Long with no association to walk (Dispute
     * today; Task and AutomationStep later). The shape is copied from
     * ScopeResolver.customerHasPocSeat, which is the house idiom for exactly this (B1).
     */
    public static Predicate customerRegionIn(Expression<Long> customerId, CriteriaQuery<?> cq,
                                             CriteriaBuilder cb, Collection<Long> permitted) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<Customer> c = sq.from(Customer.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(c.get("id"), customerId),
                c.get("region").get("id").in(permitted));
        return cb.exists(sq);
    }

    /**
     * THE PARENTHETICAL IN B3-04: "(for a deleted record, its last region)".
     *
     * <p>A hard delete takes the account row AND every {@code customer_region_history} row of it
     * (CustomerService.delete), so for a deleted account BOTH clauses of the two-clause guard —
     * the live EXISTS over {@code customers} and {@link #asOf} over the placement ledger — are
     * unsatisfiable and its whole past drops out of every as-of read for anybody who is not a
     * wildcard holder. B3's contract says the opposite: a tombstone IS served, and a reader asking
     * about January is answered about January. A wildcard holder saw it and a region-scoped reader
     * did not, for an account that was in that reader's own branch on the date they asked about.
     *
     * <p>THE MIRROR IS WHAT SURVIVES, so the mirror is what answers. HistoryWriter copies a
     * record's values forward into its tombstone, {@code region_id} among them, so
     * {@code customer_history} holds both halves this needs: the interval in force at T for "the
     * region it was in THEN", and the OPEN row — the tombstone — for "the region it was in last",
     * which is what "now" means for a record that no longer exists. This is the ONE case where
     * the mirror's own region column is read rather than the ledger (HistoryAxes says why the
     * ledger is otherwise authoritative): after the delete there is no ledger left to read.
     *
     * <p>Guarded on the account being GONE, so it is provably a no-op for every account that still
     * exists — including one that has merely moved, where the ledger is intact and answers (B1, B3).
     */
    public static Predicate lastRegionOfDeleted(Expression<Long> customerId, CriteriaQuery<?> cq,
                                                CriteriaBuilder cb, Collection<Long> permitted,
                                                LocalDate asOf) {
        Subquery<Long> alive = cq.subquery(Long.class);
        Root<Customer> c = alive.from(Customer.class);
        alive.select(cb.literal(1L)).where(cb.equal(c.get("id"), customerId));

        // The same whole-day, inclusive boundary AsOfContext.instant() uses, so a mirror interval
        // that opened at 23:59 on the asked-for day is in force on it (B3).
        Instant t = asOf.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        Subquery<Long> then = cq.subquery(Long.class);
        Root<CustomerHistory> h = then.from(CustomerHistory.class);
        then.select(cb.literal(1L)).where(cb.and(
                cb.equal(h.get("id"), customerId),
                cb.lessThanOrEqualTo(h.<Instant>get("validFrom"), t),
                cb.greaterThan(h.<Instant>get("validTo"), t),
                cb.isFalse(h.get("deleted")),
                h.get("region").get("id").in(permitted)));

        Subquery<Long> last = cq.subquery(Long.class);
        Root<CustomerHistory> open = last.from(CustomerHistory.class);
        last.select(cb.literal(1L)).where(cb.and(
                cb.equal(open.get("id"), customerId),
                cb.equal(open.<Instant>get("validTo"), AsOf.OPEN),
                open.get("region").get("id").in(permitted)));

        return cb.and(cb.not(cb.exists(alive)), cb.exists(then), cb.exists(last));
    }

    /**
     * A person is visible when they work somewhere the caller can see, when they are a customer
     * login, or when they hold no grant at all — otherwise the administrator who just created a
     * user could not see the person they made, which is a bug and not a policy (B1).
     */
    public static Predicate visiblePerson(From<?, ?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                          Collection<Long> permitted) {
        Subquery<Long> mine = cq.subquery(Long.class);
        Root<UserRegionGrant> g = mine.from(UserRegionGrant.class);
        mine.select(cb.literal(1L)).where(
                cb.equal(g.get("userId"), root.get("id")),
                // A null region on a grant is the wildcard: that person works everywhere, so they
                // are visible from any region the caller can see (B1).
                cb.or(cb.isNull(g.get("regionId")), g.get("regionId").in(permitted)));

        Subquery<Long> any = cq.subquery(Long.class);
        Root<UserRegionGrant> h = any.from(UserRegionGrant.class);
        any.select(cb.literal(1L)).where(cb.equal(h.get("userId"), root.get("id")));

        return cb.or(cb.exists(mine), cb.not(cb.exists(any)), cb.isNotNull(root.get("customerId")));
    }

    /**
     * Which region did this customer belong to on a given date? Half-open [validFrom, validTo)
     * over customer_region_history, which is authoritative for region-as-of and is the only thing
     * the region predicate ever reads for it. Because region is anchored at the customer, this one
     * shape answers as-of for every axis (B1, B3).
     */
    public static Predicate asOf(Expression<Long> customerId, CriteriaQuery<?> cq,
                                 CriteriaBuilder cb, Collection<Long> permitted, LocalDate asOf) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<CustomerRegionHistory> h = sq.from(CustomerRegionHistory.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(h.get("customerId"), customerId),
                cb.lessThanOrEqualTo(h.<LocalDate>get("validFrom"), asOf),
                cb.or(cb.isNull(h.get("validTo")), cb.greaterThan(h.<LocalDate>get("validTo"), asOf)),
                h.get("regionId").in(permitted));
        return cb.exists(sq);
    }

    /**
     * The regionId COLUMN on a table whose rows keep a bare {@code customer_id} (disputes today;
     * tasks and automation steps later). There is no association to walk, so the filter the user
     * types is answered by the same EXISTS the mandatory predicate uses — which also means a
     * user-supplied regionId can only ever narrow, because the mandatory clause is ANDed in
     * regardless and a region you cannot see simply matches nothing (B1, AUTH-08).
     */
    public static Predicate disputeRegionFilter(FilterSpec spec, Root<?> root, CriteriaQuery<?> cq,
                                                CriteriaBuilder cb) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<Customer> c = sq.from(Customer.class);
        sq.select(cb.literal(1L));
        Predicate sameCustomer = cb.equal(c.get("id"), root.get("customerId"));
        // `c.region.id` is the FK column in Hibernate 6, so asking for it costs no extra join and
        // an unplaced customer reads as NULL rather than dropping out of the subquery (B1).
        Expression<Long> regionId = c.get("region").get("id");

        return switch (spec.operator()) {
            case EQ -> {
                sq.where(cb.and(sameCustomer, cb.equal(regionId, asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(sameCustomer, cb.equal(regionId, asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(sameCustomer,
                        regionId.in(spec.values().stream().map(RegionPredicates::asLong).toList())));
                yield cb.exists(sq);
            }
            case IS_EMPTY -> {
                sq.where(cb.and(sameCustomer, cb.isNotNull(regionId)));
                yield cb.not(cb.exists(sq));
            }
            case IS_NOT_EMPTY -> {
                sq.where(cb.and(sameCustomer, cb.isNotNull(regionId)));
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for column regionId");
        };
    }

    /**
     * WHICH REGION THIS ROW'S ACCOUNT WAS IN ON THE AS-OF DATE, as a correlated scalar subquery
     * over customer_region_history — the same half-open [validFrom, validTo) window {@link #asOf}
     * already uses, because there is exactly one ledger and this is a second READING of it and not
     * a second mechanism (B1, B3).
     *
     * <p>This is the shape blueprint conflict 86 buys the whole feature with two ColumnDefs
     * instead of twelve: a mirror root maps its foreign keys as plain Longs and has no
     * {@code customer} to walk, so B1's live {@code nested2("customer","region","id")} cannot
     * resolve on InvoiceHistory, PaymentHistory, PromiseHistory, CustomerHistory, DisputeHistory
     * or TaskHistory at all.
     */
    public static Subquery<Long> asOfRegionId(Expression<Long> customerId, CriteriaQuery<?> cq,
                                              CriteriaBuilder cb, LocalDate asOf) {
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<CustomerRegionHistory> h = sq.from(CustomerRegionHistory.class);
        sq.select(h.get("regionId")).where(inForce(h, cb, customerId, asOf));
        return sq;
    }

    /**
     * The NAME of that region. Two roots and an equality rather than a join, because
     * customer_region_history keeps a bare {@code region_id} with no association to walk — the
     * same reason {@link #customerRegionName} is a subquery and not a two-hop path.
     *
     * <p>WHICH REGION is as-of; the NAME behind it is today's, because Region is not a mirrored
     * entity and a branch renamed last month renders under its new name on a January list. That
     * is contract clause a.3, and it is the one thing this pair does not reconstruct (B1, B3).
     */
    public static Subquery<String> asOfRegionName(Expression<Long> customerId, CriteriaQuery<?> cq,
                                                  CriteriaBuilder cb, LocalDate asOf) {
        Subquery<String> sq = cq.subquery(String.class);
        Root<CustomerRegionHistory> h = sq.from(CustomerRegionHistory.class);
        Root<Region> r = sq.from(Region.class);
        sq.select(r.get("name")).where(cb.and(
                inForce(h, cb, customerId, asOf),
                cb.equal(r.get("id"), h.get("regionId"))));
        return sq;
    }

    /**
     * The PATH behind RegionSchemas.AS_OF_REGION_NAME. It falls back to the live reading when no
     * as-of date is open — which is not a theoretical branch: AsOfSchemaCheck resolves every
     * as-of ColumnDef against its mirror root at BOOT, with no context on the thread, and a
     * subquery bound to a null date would be an NPE at startup rather than a wrong answer at
     * runtime (B3).
     */
    public static Expression<String> asOfRegionName(Root<?> root, CriteriaQuery<?> cq,
                                                    CriteriaBuilder cb) {
        LocalDate at = AsOfContext.date();
        return at == null
                ? customerRegionName(root, cq, cb)
                : asOfRegionName(root.<Long>get("customerId"), cq, cb, at);
    }

    /**
     * The regionId FILTER behind RegionSchemas.AS_OF_REGION_ID: {@link #disputeRegionFilter}'s
     * five arms, asked of the placement ledger at the as-of date instead of of the account's
     * current region. The EXISTS shape is kept rather than comparing against the scalar subquery
     * above, because {@code neq} and {@code isEmpty} have to stay true for a record whose account
     * was placed nowhere then, and a null-valued comparison would quietly answer false (B1, B3).
     */
    public static Predicate asOfRegionFilter(FilterSpec spec, Root<?> root, CriteriaQuery<?> cq,
                                             CriteriaBuilder cb) {
        LocalDate at = AsOfContext.date();
        if (at == null) return disputeRegionFilter(spec, root, cq, cb);

        Subquery<Long> sq = cq.subquery(Long.class);
        Root<CustomerRegionHistory> h = sq.from(CustomerRegionHistory.class);
        sq.select(cb.literal(1L));
        Predicate placedThen = inForce(h, cb, root.<Long>get("customerId"), at);
        Expression<Long> regionId = h.get("regionId");

        return switch (spec.operator()) {
            case EQ -> {
                sq.where(cb.and(placedThen, cb.equal(regionId, asLong(spec.first()))));
                yield cb.exists(sq);
            }
            case NEQ -> {
                sq.where(cb.and(placedThen, cb.equal(regionId, asLong(spec.first()))));
                yield cb.not(cb.exists(sq));
            }
            case IN -> {
                sq.where(cb.and(placedThen,
                        regionId.in(spec.values().stream().map(RegionPredicates::asLong).toList())));
                yield cb.exists(sq);
            }
            // region_id is NOT NULL on the ledger, so "placed nowhere then" is the absence of a
            // row in force and not a null column (B1, B3).
            case IS_EMPTY -> {
                sq.where(placedThen);
                yield cb.not(cb.exists(sq));
            }
            case IS_NOT_EMPTY -> {
                sq.where(placedThen);
                yield cb.exists(sq);
            }
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for column regionId");
        };
    }

    /** The one window expression the three as-of readings above share (B1, B3). */
    private static Predicate inForce(Root<CustomerRegionHistory> h, CriteriaBuilder cb,
                                     Expression<Long> customerId, LocalDate asOf) {
        return cb.and(
                cb.equal(h.get("customerId"), customerId),
                cb.lessThanOrEqualTo(h.<LocalDate>get("validFrom"), asOf),
                cb.or(cb.isNull(h.get("validTo")), cb.greaterThan(h.<LocalDate>get("validTo"), asOf)));
    }

    /**
     * The region NAME for a row that keeps a bare {@code customer_id}: a correlated scalar
     * subquery, because there is no association to walk and a two-hop LEFT join needs one (B1).
     */
    public static Expression<String> customerRegionName(Root<?> root, CriteriaQuery<?> cq,
                                                        CriteriaBuilder cb) {
        Subquery<String> sq = cq.subquery(String.class);
        Root<Customer> c = sq.from(Customer.class);
        sq.select(ColumnDef.leftJoin(c, "region").get("name"))
                .where(cb.equal(c.get("id"), root.get("customerId")));
        return sq;
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Expected an id but got: " + raw);
        }
    }
}
