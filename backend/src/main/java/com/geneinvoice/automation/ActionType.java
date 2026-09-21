package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * The THEN half of a rule (R1): the four things a rule may do once its WHERE matches. Each is
 * something a person can already do by hand from the record, and a rule does it through the same
 * service they would — so a rule can never reach further than its author could.
 */
public enum ActionType {
    CREATE_TASK,
    CREATE_PROMISE,
    CREATE_DISPUTE,
    SEND_EMAIL;

    /** How the THEN reads in the sentence the rules list shows, e.g. "then create a task". */
    public String label() {
        return switch (this) {
            case CREATE_TASK -> "create a task";
            case CREATE_PROMISE -> "create a promise";
            case CREATE_DISPUTE -> "raise a dispute";
            case SEND_EMAIL -> "send an email";
        };
    }

    /** Reads an action sent as text; an unknown one is a 400. */
    public static ActionType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (ActionType a : values()) {
            if (a.name().equalsIgnoreCase(wanted)) return a;
        }
        throw new BadRequestException("action must be one of " + Arrays.toString(values()));
    }
}
