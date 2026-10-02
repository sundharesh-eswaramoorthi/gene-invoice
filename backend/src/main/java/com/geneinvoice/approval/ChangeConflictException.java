package com.geneinvoice.approval;

import java.math.BigDecimal;
import java.util.Objects;

/** The record moved on since the change was asked for; the approval fails with this reason. */
public class ChangeConflictException extends RuntimeException {
    public ChangeConflictException(String message) {
        super(message);
    }

    /** Strict: the field must still be what the maker saw (§5.11.6). */
    public static void requireUnchanged(String label, Object base, Object current) {
        if (!same(base, current)) throw changed(label);
    }

    /**
     * Three-way, for a field the change sets: someone else may have changed it only to what the change sets it
     * to anyway. A field the change leaves alone is never a conflict.
     */
    public static void requireNotChangedElsewhere(String label, Object base, Object proposed, Object current) {
        if (same(base, proposed)) return;
        if (!same(current, base) && !same(current, proposed)) throw changed(label);
    }

    public static ChangeConflictException changed(String label) {
        return new ChangeConflictException(label + " was changed by someone else");
    }

    /** Equal, with money compared by value (500.0 is 500.00). */
    public static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }
}
