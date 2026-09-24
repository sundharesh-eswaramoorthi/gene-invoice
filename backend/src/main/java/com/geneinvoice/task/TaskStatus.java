package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * Where a piece of work has got to. Four constants and no fifth: a task is either waiting, being
 * done, finished, or called off, and anything finer is a note somebody writes in the body (A6).
 *
 * <p>DONE and CANCELLED are both terminal, which is what {@link #terminal()} exists to say once
 * rather than at every call site that has to refuse to act on a task that is already over (A6).
 */
public enum TaskStatus {
    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED;

    /** Nothing more happens to a task in this state, so every mutator refuses it by name (A6). */
    public boolean terminal() {
        return this == DONE || this == CANCELLED;
    }

    public static TaskStatus parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (TaskStatus s : values()) {
            if (s.name().equalsIgnoreCase(wanted)) return s;
        }
        throw new BadRequestException("status must be one of " + Arrays.toString(values()));
    }
}
