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

    /**
     * Digits a filter value may carry either side of the point. Postgres' {@code numeric} stops at
     * 131072 integer digits and 16383 decimal ones, and a value past that reaches the driver as a
     * zero — silently inverting the comparison — or as a 500. These bounds are the app's own and
     * far tighter: no money column holds more than a few billion, and no filter needs more than
     * six decimal places, so anything larger is a mistake and is named as one (AC-D9).
     */
    private static final int MAX_INTEGER_DIGITS = 18;
    private static final int MAX_DECIMAL_DIGITS = 6;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparable<?> coerce(Class<?> target, String raw, String column) {
        // A NUL cannot be stored or compared by the database, and trimming it away instead would
        // quietly answer a different question than the one asked (AC-D9). The value is not echoed
        // back: it is exactly the character that has no place in a response either.
        if (raw != null && raw.indexOf('\0') >= 0) {
            throw new BadRequestException(
                    "Invalid value for column " + column + ": it contains a null character");
        }
        String v = raw == null ? "" : raw.trim();
        try {
            if (target == String.class) return v;
            if (target == BigDecimal.class) return inRange(new BigDecimal(v));
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

    /**
     * The number as given, once it is small enough for the database to compare honestly. An
     * exponent past Postgres' own limits binds as zero, which turns "greater than 10^200000" into
     * a match on nearly every row, so it is refused rather than answered wrongly.
     */
    private static BigDecimal inRange(BigDecimal d) {
        if (d.precision() - d.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("the number is too large to compare");
        }
        if (d.scale() > MAX_DECIMAL_DIGITS) {
            throw new IllegalArgumentException(
                    "more than " + MAX_DECIMAL_DIGITS + " decimal places");
        }
        return d;
    }

    /** Accepts a full ISO instant or a bare yyyy-MM-dd, which is read as UTC start of day. */
    static Instant parseInstant(String v) {
        if (isDateOnly(v)) {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(v);
    }

    /** A bare yyyy-MM-dd rather than a full instant. */
    static boolean isDateOnly(String v) {
        return v.length() == 10 && v.charAt(4) == '-';
    }
}
