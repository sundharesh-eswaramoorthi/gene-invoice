package com.geneinvoice.region;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.ColumnDef;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The region axis of every query, and the only two ways past a caller's own grants.
 *
 * <p>The predicate is built here and added by TableQueryExecutor itself rather than passed by a
 * caller, so the axis is a property of the query and not of anyone remembering: an endpoint
 * written next year that passes an empty scope list is still region-bounded. A denial is always an
 * empty result (cb.disjunction()) and never an exception — a record your regions exclude reads as
 * one that does not exist, which is AUTH-08 and is never relaxed (B1).
 */
@Component
@RequiredArgsConstructor
public class RegionScope {

    /** The locked chip a list carries when the caller can see no region at all. */
    public static final String NO_REGION_FILTER = "regionId:isEmpty:";

    private final CurrentUser currentUser;

    /**
     * The complete, enumerable set of reasons a thread may leave a caller's own grants behind.
     * A new use is a new constant, which shows up in review, and RegionCoverageTest asserts the
     * constants match the call sites found by reflection (B1).
     */
    public enum SystemReason {
        SCHEMA_UPGRADE,
        DATA_SEED,
        MAIL_WEBHOOK,
        EMAIL_DISPATCH,
        PROMISE_SWEEP,
        HISTORY_RECONCILE,
        /** Narrowing only: the automation consumer has no principal and must not widen (A1, B1). */
        AUTOMATION_FANOUT,
        /** Narrowing only: an action runs inside the regions its own rule names (A3, B1). */
        AUTOMATION_ACT
    }

    /**
     * @param why       why this thread is not reading a person's grants
     * @param regionIds null widens to every region (asSystem); a set REPLACES the grant-derived
     *                  ids with an explicit, bounded one (asRegions). An EMPTY set denies, because
     *                  a rule whose author has no grants must reach nothing rather than everything.
     */
    private record Ambient(SystemReason why, Set<Long> regionIds) {
    }

    private static final ThreadLocal<Ambient> AMBIENT = new ThreadLocal<>();

    /** The ONE widening hatch: a caller-free path that is entitled to every region (B1). */
    public static <T> T asSystem(SystemReason why, Supplier<T> body) {
        return within(new Ambient(why, null), body);
    }

    public static void asSystem(SystemReason why, Runnable body) {
        within(new Ambient(why, null), () -> {
            body.run();
            return null;
        });
    }

    /**
     * The ONE narrowing hatch. The automation consumer runs on a daemon thread with no principal,
     * so its grants would be none() and every rule would match nothing; it must instead be bounded
     * by its own rule's regions, which is narrower than a person and narrower still than asSystem.
     * The consumer NEVER calls asSystem (A5, B1 INTEGRATION).
     */
    public static <T> T asRegions(Set<Long> regionIds, SystemReason why, Supplier<T> body) {
        return within(new Ambient(why, Set.copyOf(regionIds)), body);
    }

    public static void asRegions(Set<Long> regionIds, SystemReason why, Runnable body) {
        within(new Ambient(why, Set.copyOf(regionIds)), () -> {
            body.run();
            return null;
        });
    }

    private static <T> T within(Ambient ambient, Supplier<T> body) {
        Ambient before = AMBIENT.get();
        AMBIENT.set(ambient);
        try {
            return body.get();
        } finally {
            // Restoring rather than removing keeps a nested hatch honest: an asRegions inside an
            // asSystem must give the outer one back, not clear the thread (B1).
            if (before == null) {
                AMBIENT.remove();
            } else {
                AMBIENT.set(before);
            }
        }
    }

    /** Non-null when this thread is inside a hatch. RegionAccess asks before it asks the caller. */
    static SystemReason systemReason() {
        Ambient ambient = AMBIENT.get();
        return ambient == null ? null : ambient.why();
    }

    /** The explicit region set a narrowing hatch installed, or null for no hatch and for asSystem. */
    static Set<Long> ambientRegions() {
        Ambient ambient = AMBIENT.get();
        return ambient == null ? null : ambient.regionIds();
    }

    /**
     * Null when nothing is to be added — an unregioned entity, a wildcard holder, a customer login
     * or a widened system thread. Never throws for a denial (AUTH-08).
     *
     * @param atLeast the level the read needs; VIEW for every list
     * @param asOf    the date to resolve the placement at, or null for "now"
     */
    public Predicate predicate(From<?, ?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                               RegionRight atLeast, LocalDate asOf) {
        RegionAxis axis = RegionAxes.of(root.getJavaType());
        if (axis == RegionAxis.NONE) return null;

        LocalDate at = effectiveAsOf(asOf);

        Ambient ambient = AMBIENT.get();
        if (ambient != null) {
            if (ambient.regionIds() == null) return null;
            if (ambient.regionIds().isEmpty()) return cb.disjunction();
            return clause(axis, root, cq, cb, ambient.regionIds(), at);
        }

        // No principal and no hatch: "everything" is the one answer that is certainly wrong, and a
        // headless caller that needs to read is expected to name its reason (B1).
        if (currentUser.idOrNull() == null) return cb.disjunction();
        // A customer login holds no grants and needs none: it is already pinned to its own account
        // by customer_id, so every pre-B1 customer-scoping behaviour is preserved bit for bit (B1).
        if (currentUser.isCustomer()) return null;

        RegionGrants grants = currentUser.grants();
        // allRegions FIRST: with() returns an empty set for a wildcard holder too, and reading the
        // two in the other order silently grants everything to a user with no grant at all (B1).
        if (grants.allRegions(atLeast)) return null;
        Set<Long> ids = grants.with(atLeast);
        if (ids.isEmpty()) return cb.disjunction();
        return clause(axis, root, cq, cb, ids, at);
    }

    private static Predicate clause(RegionAxis axis, From<?, ?> root, CriteriaQuery<?> cq,
                                    CriteriaBuilder cb, Set<Long> ids, LocalDate at) {
        Predicate live = switch (axis) {
            // `x.assoc.id` is the FK column in Hibernate 6, so the last hop costs no extra join.
            case OWN -> root.get("region").get("id").in(ids);
            case OWN_ID -> root.get("regionId").in(ids);
            case VIA_CUSTOMER -> ColumnDef.leftJoin(root, "customer").get("region").get("id").in(ids);
            case VIA_CUSTOMER_ID -> RegionPredicates.customerRegionIn(root.<Long>get("customerId"), cq, cb, ids);
            case VIA_USER_GRANTS -> RegionPredicates.visiblePerson(root, cq, cb, ids);
            case NONE -> null;
        };
        if (at == null || live == null) return live;
        Expression<Long> customerId = customerId(axis, root);
        if (customerId == null) return live;
        // A record is visible under ?asOf only if the caller may see where it was THEN and where it
        // is NOW: reading the past must not hand over a row that has since moved out of reach, and
        // must not hide one the caller can see today (B3-04's leak guard) (B1, B3).
        //
        // OR the record's account has been DELETED, which is B3-04's own parenthetical — "for a
        // deleted record, its last region" — and the one case both clauses above answer "no" to
        // for a reason that has nothing to do with the reader: the delete takes the customers row
        // and every placement row with it, so the account is in no branch on any date and a
        // region-scoped reader lost the whole of its past while a wildcard holder kept it. The
        // fallback reads the customer mirror, which is what survives a delete (B1, B3).
        return cb.or(
                cb.and(live, RegionPredicates.asOf(customerId, cq, cb, ids, at)),
                RegionPredicates.lastRegionOfDeleted(customerId, cq, cb, ids, at));
    }

    /** The customer whose placement answers as-of for this axis, or null where there is none. */
    private static Expression<Long> customerId(RegionAxis axis, From<?, ?> root) {
        return switch (axis) {
            case OWN -> root.<Long>get("id");
            case VIA_CUSTOMER -> ColumnDef.leftJoin(root, "customer").<Long>get("id");
            case VIA_CUSTOMER_ID -> root.<Long>get("customerId");
            // A person and a flat region_id have no customer to ask, so they read the same at every
            // date: their live clause already IS the answer (B1).
            case OWN_ID, VIA_USER_GRANTS, NONE -> null;
        };
    }

    private static LocalDate effectiveAsOf(LocalDate asOf) {
        // B3-CONTEXT made this read AsOfContext.date() when the argument is null, so the one call
        // the executor makes is correct for a live root and a mirror root alike and B3 needs no
        // second injection point of its own (B1, B3).
        //
        // This ONE line is the whole of B3's region story, and it is what turns the two-clause
        // leak guard above from dead code into live code: an explicit argument still wins, so
        // every existing caller is unchanged, and null — which is what the executor passes — now
        // means "the date this thread is answering as of" instead of "now" (B3).
        return asOf != null ? asOf : AsOfContext.date();
    }

    /**
     * The chips a page tells its reader it is already narrowed by: "regionId:in:3,7",
     * "regionId:isEmpty:", or nothing at all for a caller who can see every region (B1).
     */
    public List<String> lockedFilters(Class<?> entityType) {
        if (RegionAxes.of(entityType) == RegionAxis.NONE) return List.of();

        Ambient ambient = AMBIENT.get();
        if (ambient != null) {
            return ambient.regionIds() == null ? List.of() : chips(ambient.regionIds());
        }
        Long me = currentUser.idOrNull();
        // A customer login is narrowed by its account and not by a branch, and saying "Regions: …"
        // to a customer would name branches they have no business knowing about (B1).
        if (me != null && currentUser.isCustomer()) return List.of();

        RegionGrants grants = currentUser.grants();
        if (grants.allRegions(RegionRight.VIEW)) return List.of();   // mirrors Scope.empty()
        return chips(grants.with(RegionRight.VIEW));
    }

    private static List<String> chips(Set<Long> ids) {
        // customers.region_id is NOT NULL, so "the region is empty" literally IS the empty set and
        // the existing isEmpty operator says it with no new grammar (B1).
        return ids.isEmpty()
                ? List.of(NO_REGION_FILTER)
                : List.of("regionId:in:" + ids.stream().sorted()
                        .map(String::valueOf).collect(Collectors.joining(",")));
    }
}
