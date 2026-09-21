package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

final class ValueCoercion {
    private ValueCoercion() {}

    private static final int MAX_INTEGER_DIGITS = 18;
    private static final int MAX_DECIMAL_DIGITS = 6;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparable<?> coerce(Class<?> target, String raw, String column) {
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

    static Instant parseInstant(String v) {
        if (isDateOnly(v)) {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(v);
    }

    static boolean isDateOnly(String v) {
        return v.length() == 10 && v.charAt(4) == '-';
    }
}
