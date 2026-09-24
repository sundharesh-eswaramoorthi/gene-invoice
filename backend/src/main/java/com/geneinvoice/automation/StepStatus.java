package com.geneinvoice.automation;

/**
 * Where one unit of work has got to (A5).
 *
 * <p>SKIPPED and POISONED are both terminal and they mean different things, which is why there
 * are two: SKIPPED is "this correctly did not happen" (the record stopped matching, the author
 * lost the privilege, the dispute was already open) and POISONED is "this kept failing and
 * nobody is going to try again". A queue that collapses them reports a bug as a business
 * decision (A5).
 */
public enum StepStatus {

    QUEUED,
    RUNNING,
    DONE,
    SKIPPED,
    POISONED;

    public boolean terminal() {
        return this == DONE || this == SKIPPED || this == POISONED;
    }
}
