package com.geneinvoice.history;

import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.JpaEntityJoin;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Criteria written once and right in both modes (§5.7.12): while a point is bound, a query over a versioned record
 * reads its versions valid at the point; otherwise it reads the live table, with exactly the SQL it always had.
 * Every {@code from}, association join and subquery over a versioned record in the query code goes through here.
 */
public final class Temporal {

    private Temporal() {}

    /** The body of a {@link #subquery}: adds its conditions to {@code where} and returns what it selects. */
    @FunctionalInterface
    public interface SubBody<R> {
        Expression<R> build(Root<?> root, Subquery<R> sq, List<Predicate> where);
    }

    /** A root over {@code live}: its versions while a point is bound. */
    public static Root<?> from(AbstractQuery<?> q, Class<?> live) {
        return q.from(HistoryModel.rootFor(live));
    }

    /** "Valid at the bound point" for a from over versions; null for a live one, which needs no condition. */
    public static Predicate validOrNull(From<?, ?> f, CriteriaBuilder cb) {
        AsOfPoint point = AsOf.current();
        if (point == null || !HistoryModel.isVersionClass(f.getJavaType())) return null;
        Instant p = point.instant();
        return cb.and(cb.lessThanOrEqualTo(f.<Instant>get("validFrom"), p), cb.greaterThan(f.<Instant>get("validTo"), p));
    }

    /** {@link #from}, with its validity condition added to {@code where}. */
    public static Root<?> fromValid(AbstractQuery<?> q, Class<?> live, CriteriaBuilder cb, List<Predicate> where) {
        Root<?> root = from(q, live);
        Predicate valid = validOrNull(root, cb);
        if (valid != null) where.add(valid);
        return root;
    }

    /**
     * An association followed from {@code from}. From a live record it is the plain join (a left join is reused, as
     * {@link ColumnDef#leftJoin} always did). From a version it reaches the versions of the record pointed at, valid
     * at the same point, when the association is one history follows (a customer's name at the time); otherwise the
     * live record (a POC, a region), whose names are always today's.
     */
    @SuppressWarnings("rawtypes")
    public static From<?, ?> join(From<?, ?> from, String attribute, JoinType type, CriteriaBuilder cb) {
        Class<?> fromType = from.getJavaType();
        HistoryModel.Navigation nav = HistoryModel.isVersionClass(fromType)
                ? HistoryModel.navigation(fromType, attribute).orElse(null) : null;
        if (nav == null) {
            return type == JoinType.LEFT ? ColumnDef.leftJoin(from, attribute) : ((From) from).join(attribute, type);
        }
        String alias = "t_" + attribute + "_" + type;
        // Entity joins are not among getJoins(); they are found again by their alias.
        for (SqmJoin<?, ?> existing : ((SqmFrom<?, ?>) from).getSqmJoins()) {
            if (alias.equals(existing.getAlias())) return existing;
        }
        JpaEntityJoin<?> j = ((JpaFrom<?, ?>) from).join(nav.target(), SqmJoinType.from(type));
        j.alias(alias);
        List<Predicate> on = new ArrayList<>();
        on.add(nav.on().on(j, from, cb));
        Predicate valid = validOrNull(j, cb);
        if (valid != null) on.add(valid);
        j.on(cb.and(on.toArray(new Predicate[0])));
        return j;
    }

    /** A subquery over {@code live} (its versions valid at the point while one is bound), built by {@code body}. */
    public static <R> Subquery<R> subquery(CommonAbstractCriteria parent, Class<R> result, Class<?> live,
                                           CriteriaBuilder cb, SubBody<R> body) {
        Subquery<R> sq = parent.subquery(result);
        List<Predicate> where = new ArrayList<>();
        Root<?> root = fromValid(sq, live, cb, where);
        Expression<R> selection = body.build(root, sq, where);
        sq.select(selection).where(where.toArray(new Predicate[0]));
        return sq;
    }
}
