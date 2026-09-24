package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.invoice.InvoiceDates;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FilterPredicates {
    private FilterPredicates() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Predicate build(FilterSpec spec, ColumnDef def, Root<?> root,
                           CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (def.customFilter() != null) {
            return def.customFilter().resolve(spec, root, query, cb);
        }
        Expression<?> path = def.path().resolve(root, query, cb);
        Class<?> javaType = path.getJavaType();
        String column = def.name();

        return switch (spec.operator()) {
            case IS_EMPTY -> javaType == String.class
                    ? cb.or(cb.isNull(path), cb.equal(cb.trim((Expression<String>) path), ""))
                    : cb.isNull(path);
            case IS_NOT_EMPTY -> javaType == String.class
                    ? cb.and(cb.isNotNull(path), cb.notEqual(cb.trim((Expression<String>) path), ""))
                    : cb.isNotNull(path);
            case CONTAINS -> cb.like(
                    cb.lower((Expression<String>) path),
                    "%" + Strings.escapeLike(spec.first().toLowerCase(Locale.ROOT)) + "%",
                    Strings.LIKE_ESCAPE);
            case EQ -> cb.equal(path, ValueCoercion.coerce(javaType, spec.first(), column));
            case NEQ -> cb.or(cb.isNull(path),
                    cb.notEqual(path, ValueCoercion.coerce(javaType, spec.first(), column)));
            case IN -> path.in(coerceAll(javaType, spec.values(), column));
            case NOT_IN -> cb.or(cb.isNull(path),
                    cb.not(path.in(coerceAll(javaType, spec.values(), column))));
            case GT -> cb.greaterThan((Expression<Comparable>) path,
                    (Comparable) ValueCoercion.coerce(javaType, spec.first(), column));
            case GTE -> cb.greaterThanOrEqualTo((Expression<Comparable>) path,
                    (Comparable) ValueCoercion.coerce(javaType, spec.first(), column));
            case LT -> cb.lessThan((Expression<Comparable>) path,
                    (Comparable) ValueCoercion.coerce(javaType, spec.first(), column));
            case LTE -> atMost(path, javaType, spec.first(), column, cb);
            case BETWEEN -> cb.and(
                    cb.greaterThanOrEqualTo((Expression<Comparable>) path,
                            (Comparable) ValueCoercion.coerce(javaType, spec.values().get(0), column)),
                    atMost(path, javaType, spec.values().get(1), column, cb));
            case RELATIVE -> relative(spec, def, path, cb);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate relative(FilterSpec spec, ColumnDef def, Expression<?> path, CriteriaBuilder cb) {
        if (def.type() != ColumnType.DATE) {
            throw new BadRequestException("relative is only valid on date columns");
        }
        // Through the read clock and not straight to the wall clock: this was the one date preset
        // in the codebase that bypassed the chokepoint, so relative:last30Days under ?asOf now
        // means the thirty days ending on the date that was asked for (B3).
        DateRange range = DateRange.preset(spec.first(), InvoiceDates.today());
        Class<?> javaType = path.getJavaType();
        List<Predicate> parts = new ArrayList<>();
        if (range.from() != null) {
            Comparable<?> lo = javaType == LocalDate.class
                    ? range.from()
                    : range.from().atStartOfDay(ZoneOffset.UTC).toInstant();
            parts.add(cb.greaterThanOrEqualTo((Expression<Comparable>) path, (Comparable) lo));
        }
        if (range.toInclusive() != null) {
            parts.add(javaType == LocalDate.class
                    ? cb.lessThanOrEqualTo((Expression<Comparable>) path, (Comparable) range.toInclusive())
                    : cb.lessThan((Expression<Comparable>) path, (Comparable) range.toInclusive()
                            .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        return parts.isEmpty() ? cb.conjunction() : cb.and(parts.toArray(new Predicate[0]));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate atMost(Expression<?> path, Class<?> javaType, String raw, String column,
                                    CriteriaBuilder cb) {
        if (isDateOnlyInstant(javaType, raw)) {
            return cb.lessThan((Expression<Comparable>) path, (Comparable) nextDayStart(raw, column));
        }
        return cb.lessThanOrEqualTo((Expression<Comparable>) path,
                (Comparable) ValueCoercion.coerce(javaType, raw, column));
    }

    private static boolean isDateOnlyInstant(Class<?> javaType, String raw) {
        return javaType == Instant.class && raw != null && ValueCoercion.isDateOnly(raw.trim());
    }

    private static Instant nextDayStart(String raw, String column) {
        try {
            return LocalDate.parse(raw.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid date for column " + column + ": " + raw);
        }
    }

    private static List<?> coerceAll(Class<?> javaType, List<String> values, String column) {
        return values.stream().map(v -> (Object) ValueCoercion.coerce(javaType, v, column)).toList();
    }
}
