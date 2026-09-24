package com.geneinvoice.automation;

/**
 * Where one scheduled or manual run has got to (A5).
 *
 * <p>FANNING is "still finding records", RUNNING is "every step is planned and they are being
 * worked", and SKIPPED_OVERRUN is the honest answer when yesterday's run of the same rule has not
 * finished: the slot is recorded as skipped rather than silently dropped, so somebody reading the
 * history sees that the rule fell behind (A5).
 */
public enum RunStatus {

    FANNING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED_OVERRUN;

    public boolean live() {
        return this == FANNING || this == RUNNING;
    }
}
