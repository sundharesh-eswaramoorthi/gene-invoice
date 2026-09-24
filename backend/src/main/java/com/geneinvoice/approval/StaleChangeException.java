package com.geneinvoice.approval;

/**
 * The record a waiting change is about is no longer the record that was approved — its row version
 * moved, its payload was written under an older PAYLOAD_VERSION, or it has gone entirely. The 409
 * family: the approver is told to look again rather than being handed a replay onto a record
 * nobody agreed to (B2).
 */
public class StaleChangeException extends RuntimeException {

    public StaleChangeException(String message) {
        super(message);
    }
}
