package com.geneinvoice.automation;

/**
 * What a finished step actually made (A5).
 *
 * <p>PENDING_CHANGE is a SUCCESS and not a failure: an action whose exposure is over the region's
 * approval threshold is parked by ApprovalGate, and the honest answer for the run history is
 * "made a change that is waiting for somebody" rather than an error nobody can act on. The run
 * reads "3 created, 2 awaiting approval" (A5, B2 INTEGRATION).
 */
public enum ProducedType {

    TASK,
    PROMISE,
    DISPUTE,
    EMAIL,
    PENDING_CHANGE
}
