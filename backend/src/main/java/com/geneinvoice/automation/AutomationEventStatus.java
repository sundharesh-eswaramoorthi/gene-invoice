package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * Where one outbox row stands (R3). QUEUED and RUNNING are the only two a worker may act on;
 * the other three are settled and nothing touches them again.
 *
 * <p>SKIPPED is held apart from DONE and from FAILED on purpose: it means the rule was asked and
 * had nothing to do — the WHERE did not match, the record has since gone, the rule was disabled or
 * deleted — which is the ordinary outcome for most rows and must not read as an error on the runs
 * list. FAILED means the app could not tell whether the action happened.
 *
 * <p>It is also the only settled status that gives its de-duplication key up, for the same reason:
 * a run that did nothing has nothing to de-duplicate against ({@link AutomationEvent#releasedKey}).
 */
public enum AutomationEventStatus {
    QUEUED,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED;

    public static AutomationEventStatus parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (AutomationEventStatus s : values()) {
            if (s.name().equalsIgnoreCase(wanted)) return s;
        }
        throw new BadRequestException("status must be one of " + Arrays.toString(values()));
    }
}
