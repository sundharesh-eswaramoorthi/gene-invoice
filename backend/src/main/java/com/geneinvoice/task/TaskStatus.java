package com.geneinvoice.task;

/** New constants are appended last: the stale CHECK is found through the first one (D12). */
public enum TaskStatus {
    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED;

    /** Still to be done: counted as open work and as overdue once its due date passes. */
    public boolean isOpenWork() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
