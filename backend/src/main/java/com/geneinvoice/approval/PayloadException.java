package com.geneinvoice.approval;

/** A stored request that can no longer be read or no longer passes validation; the approval fails with it. */
public class PayloadException extends RuntimeException {
    public PayloadException(String message) {
        super(message);
    }

    public PayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
