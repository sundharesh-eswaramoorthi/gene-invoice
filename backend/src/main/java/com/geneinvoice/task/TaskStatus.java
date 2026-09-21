package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * Where a piece of work has got to (T2). Unlike a promise's status, which is recomputed from
 * payment facts, a task's is only ever what a person said it is: nothing in the system can know
 * that somebody has chased a customer, so there is no evaluation to run and no override to record.
 *
 * <p>{@link #DONE} and {@link #CANCELLED} both mean "off the list", and are told apart only for
 * the reader: the work happened, or it never needed to. Both are {@link #isTerminal()}, which is
 * what the open-count badge, the overdue flag and the completion stamp all key on, so a new
 * closing status is one constant here and nothing else.
 */
public enum TaskStatus {
    OPEN("Open"),
    IN_PROGRESS("In progress"),
    DONE("Done"),
    CANCELLED("Cancelled");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    /** How the status reads to a person: "In progress", not "IN_PROGRESS". */
    public String label() {
        return label;
    }

    /** True once the task is off the list, however it got there. */
    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }

    /** Reads a status sent as text, in a request body or a rule's stored config; an unknown one is a 400. */
    public static TaskStatus parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (TaskStatus s : values()) {
            if (s.name().equalsIgnoreCase(wanted)) return s;
        }
        throw new BadRequestException("status must be one of " + Arrays.toString(values()));
    }
}
