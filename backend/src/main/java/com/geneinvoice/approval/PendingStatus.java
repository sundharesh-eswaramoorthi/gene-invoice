package com.geneinvoice.approval;

/** Where a pending change stands. Every status but PENDING is final and has a {@code decided_at}. */
public enum PendingStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    SUPERSEDED,
    FAILED,
    CLOSED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
