package com.geneinvoice.approval;

/**
 * Where a held change stands. Exactly one of these is "waiting": PENDING is the only status that
 * stamps a pending_key, so the one-open-change-per-record rule is the database's business and not
 * a Java exists() two concurrent makers can both pass (B2).
 */
public enum PendingChangeStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    SUPERSEDED
}
