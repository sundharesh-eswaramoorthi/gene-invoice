package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Turns a filter's string value into the Java type the mapped column actually holds. */
final class ValueCoercion {
    private ValueCoercion() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparable<?> coerce(Class<?> target, String raw, String column) {
        String v = raw == null ? "" : raw.trim();
        try {
            if (target == String.class) return v;
            if (target == BigDecimal.class) return new BigDecimal(v);
            if (target == Long.class || target == long.class) return Long.valueOf(v);
            if (target == Integer.class || target == int.class) return Integer.valueOf(v);
            if (target == Double.class || target == double.class) return Double.valueOf(v);
            if (target == Boolean.class || target == boolean.class) {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("expected true or false");
                }
                return Boolean.valueOf(v);
            }
            if (target == Instant.class) return parseInstant(v);
            if (target == LocalDate.class) return LocalDate.parse(v);
            if (target.isEnum()) return (Comparable<?>) Enum.valueOf((Class<Enum>) target, v.toUpperCase());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid value for column " + column + ": '" + raw + "' (" + e.getMessage() + ")");
        }
        throw new BadRequestException("Column " + column + " cannot be filtered on this value type");
    }

    /** Accepts a full ISO instant or a bare yyyy-MM-dd, which is read as UTC start of day. */
    static Instant parseInstant(String v) {
        if (v.length() == 10 && v.charAt(4) == '-') {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(v);
    }

    /** The upper bound of a date-only value, so `lte 2026-01-31` includes all of the 31st. */
    static Instant endOfDayIfDateOnly(String v) {
        if (v.length() == 10 && v.charAt(4) == '-') {
            return LocalDate.parse(v).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        }
        return Instant.parse(v);
    }
}
