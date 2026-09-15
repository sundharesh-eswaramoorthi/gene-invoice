package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.math.BigDecimal;

/** Small helpers shared by the filter-aware summary tiles. */
public final class Aggregates {

    private Aggregates() {}

    /** COUNT(*) FILTER (WHERE p), expressed portably so it works on both H2 and Postgres. */
    public static Expression<Long> countWhen(CriteriaBuilder cb, Predicate p) {
        return cb.coalesce(cb.sum(cb.<Long>selectCase().when(p, 1L).otherwise(0L)), 0L);
    }

    /** SUM(expr) restricted to rows matching p, zero elsewhere. */
    public static Expression<BigDecimal> sumWhen(CriteriaBuilder cb, Predicate p,
                                                 Expression<BigDecimal> value) {
        return cb.coalesce(cb.sum(cb.<BigDecimal>selectCase()
                .when(p, value).otherwise(cb.literal(BigDecimal.ZERO))), BigDecimal.ZERO);
    }

    public static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** Aggregate results come back as BigDecimal on Postgres and can be Double on H2. */
    public static BigDecimal asMoney(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b.setScale(2, java.math.RoundingMode.HALF_UP);
        return new BigDecimal(o.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
