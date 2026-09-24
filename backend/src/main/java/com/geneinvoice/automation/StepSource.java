package com.geneinvoice.automation;

/**
 * Which door a step came in through, kept on the row so the run history can say "this happened
 * because the invoice changed" rather than leaving a reader to infer it from a null run_id (A5).
 */
public enum StepSource {

    EVENT,
    SCHEDULE,
    MANUAL
}
