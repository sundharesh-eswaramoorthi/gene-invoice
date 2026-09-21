package com.geneinvoice.assignee;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of record that carry assignees (A1). All three take the same several-assignee list, so
 * one child table answers for them rather than three that would drift apart; which parent a row
 * belongs to is this value beside its id, the shape {@code Document} and {@code Email} already use
 * for the same job.
 */
public enum AssigneeOwnerType {
    TASK,
    PROMISE,
    DISPUTE;

    /** What one record is called in a sentence, e.g. "this promise". */
    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reads a type sent as text; an unknown one is a 400. */
    public static AssigneeOwnerType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (AssigneeOwnerType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("ownerType must be one of " + Arrays.toString(values()));
    }
}
