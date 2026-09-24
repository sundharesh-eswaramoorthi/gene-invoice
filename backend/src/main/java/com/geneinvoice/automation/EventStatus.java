package com.geneinvoice.automation;

/**
 * Where one outbox row has got to (A5).
 *
 * <p>NEW and FAILED are both due — a failed fan-out is retried with a backoff, which is what makes
 * "nothing is lost if the consumer is down" true of a consumer that is up but broken as well as of
 * one that is not running at all. FANNING is the claimed state, so two instances sweeping the same
 * row cannot both turn it into steps. DONE is terminal and is what retention deletes.
 */
public enum EventStatus {

    NEW,
    FANNING,
    DONE,
    FAILED
}
