package com.geneinvoice.automation.run;

/** Where one run of a rule stands (§3.6). Stored by name; new constants are appended last (D12). */
public enum RunStatus {
    QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED, SUPERSEDED;

    /** Finished one way or another: nothing more is done for the run's event. */
    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
