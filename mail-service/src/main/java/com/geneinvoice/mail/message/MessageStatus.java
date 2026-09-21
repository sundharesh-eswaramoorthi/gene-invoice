package com.geneinvoice.mail.message;

/** Where one copy stands (M8). A copy only moves forward; {@link #DELIVERED} turns {@link #BOUNCED} only when estimated. */
public enum MessageStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    BOUNCED,
    FAILED,
    NOT_SENT;

    public boolean retryable() {
        return this == FAILED || this == NOT_SENT;
    }
}
