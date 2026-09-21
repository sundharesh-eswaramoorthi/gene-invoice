package com.geneinvoice.common.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.math.BigDecimal;

public final class Aggregates {

    private Aggregates() {}

    public static Expression<Long> countWhen(CriteriaBuilder cb, Predicate p) {
        return cb.coalesce(cb.sum(cb.<Long>selectCase().when(p, 1L).otherwise(0L)), 0L);
    }

    public static Expression<BigDecimal> sumWhen(CriteriaBuilder cb, Predicate p,
                                                 Expression<BigDecimal> value) {
        return cb.coalesce(cb.sum(cb.<BigDecimal>selectCase()
                .when(p, value).otherwise(cb.literal(BigDecimal.ZERO))), BigDecimal.ZERO);
    }

    public static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    public static BigDecimal asMoney(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b.setScale(2, java.math.RoundingMode.HALF_UP);
        return new BigDecimal(o.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
