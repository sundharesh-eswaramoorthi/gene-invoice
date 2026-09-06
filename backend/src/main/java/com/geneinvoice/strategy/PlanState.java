package com.geneinvoice.strategy;

/** Lifecycle of a frozen per-recipient delivery plan: PENDING is due (or awaiting its next
 *  hourly retry), IN_PROGRESS is claimed by one worker, DELIVERED is terminal success, FAILED
 *  is terminal after the initial attempt and three hourly retries all failed. */
public enum PlanState {
    PENDING,
    IN_PROGRESS,
    DELIVERED,
    FAILED
}
