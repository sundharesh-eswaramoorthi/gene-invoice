package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * What sets a rule off (R1). The first two are something a person did to a record, noticed where
 * the record is saved; the last two are the clock, swept by {@link AutomationScheduler}. They are
 * one enum rather than two because a rule is written the same way whichever it is — the WHEN
 * picker offers all four and nothing else about the rule changes.
 */
public enum TriggerKind {
    CREATED,
    UPDATED,
    DAILY,
    WEEKLY;

    /** True for the two the clock raises, which have no record behind them until a run finds one. */
    public boolean isScheduled() {
        return this == DAILY || this == WEEKLY;
    }

    /** How the WHEN reads in the sentence the rules list shows, e.g. "When an invoice is updated". */
    public String label() {
        return switch (this) {
            case CREATED -> "is created";
            case UPDATED -> "is updated";
            case DAILY -> "every day";
            case WEEKLY -> "every week";
        };
    }

    /** Reads a trigger sent as text; an unknown one is a 400. */
    public static TriggerKind parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (TriggerKind t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("trigger must be one of " + Arrays.toString(values()));
    }
}
