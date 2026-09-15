package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;
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

/** Builds a criteria {@link Predicate} for one validated filter chip. */
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
                    "%" + escapeLike(spec.first().toLowerCase(Locale.ROOT)) + "%", '\\');
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
            case LTE -> cb.lessThanOrEqualTo((Expression<Comparable>) path, (Comparable) upperBound(javaType, spec.first(), column));
            case BETWEEN -> cb.between((Expression<Comparable>) path,
                    (Comparable) ValueCoercion.coerce(javaType, spec.values().get(0), column),
                    (Comparable) upperBound(javaType, spec.values().get(1), column));
            case RELATIVE -> relative(spec, def, path, cb);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate relative(FilterSpec spec, ColumnDef def, Expression<?> path, CriteriaBuilder cb) {
        if (def.type() != ColumnType.DATE) {
            throw new BadRequestException("relative is only valid on date columns");
        }
        DateRange range = DateRange.preset(spec.first(), LocalDate.now(ZoneOffset.UTC));
        Class<?> javaType = path.getJavaType();
        List<Predicate> parts = new ArrayList<>();
        if (range.from() != null) {
            Comparable<?> lo = javaType == LocalDate.class
                    ? range.from()
                    : range.from().atStartOfDay(ZoneOffset.UTC).toInstant();
            parts.add(cb.greaterThanOrEqualTo((Expression<Comparable>) path, (Comparable) lo));
        }
        if (range.toInclusive() != null) {
            Comparable<?> hi = javaType == LocalDate.class
                    ? range.toInclusive()
                    : (Instant) range.toInclusive().plusDays(1).atStartOfDay(ZoneOffset.UTC)
                            .toInstant().minusNanos(1);
            parts.add(cb.lessThanOrEqualTo((Expression<Comparable>) path, (Comparable) hi));
        }
        return parts.isEmpty() ? cb.conjunction() : cb.and(parts.toArray(new Predicate[0]));
    }

    /** For `lte`/`between` on a timestamp column, a bare date must include the whole day. */
    private static Comparable<?> upperBound(Class<?> javaType, String raw, String column) {
        if (javaType == Instant.class) {
            try {
                return ValueCoercion.endOfDayIfDateOnly(raw.trim());
            } catch (RuntimeException e) {
                throw new BadRequestException("Invalid date for column " + column + ": " + raw);
            }
        }
        return ValueCoercion.coerce(javaType, raw, column);
    }

    private static List<?> coerceAll(Class<?> javaType, List<String> values, String column) {
        return values.stream().map(v -> (Object) ValueCoercion.coerce(javaType, v, column)).toList();
    }

    /** Neutralise LIKE wildcards so a user's `%` is matched literally. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
