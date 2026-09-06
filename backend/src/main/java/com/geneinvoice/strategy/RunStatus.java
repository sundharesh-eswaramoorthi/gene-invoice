package com.geneinvoice.strategy;

/** Lifecycle of a persisted strategy run: ACTIVE is under execution, RETRY_PENDING awaits its
 *  next hourly attempt, COMPLETED means evaluation and plan creation finished, FAILED is
 *  terminal after the initial attempt and three hourly retries all failed. */
public enum RunStatus {
    ACTIVE,
    RETRY_PENDING,
    COMPLETED,
    FAILED
}
