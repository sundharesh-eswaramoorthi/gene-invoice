package com.geneinvoice.mail.message;

/** Where one copy stands (M8). A copy only moves forward; {@link #DELIVERED} turns {@link #BOUNCED} only when estimated. */
public enum MessageStatus {
    /** Waiting for a worker, perhaps for a retry's wait to end. */
    QUEUED,
    /** A worker has it. */
    SENDING,
    /** Gmail accepted it. */
    SENT,
    /** Seen in the recipient's own Gmail (confirmed), or no bounce came back in time (estimated). */
    DELIVERED,
    /** The recipient's own Gmail shows it read. */
    READ,
    /** A delivery-failure notice came back to the sender's mailbox. */
    BOUNCED,
    /** Gmail refused it, or every attempt failed. */
    FAILED,
    /** Never tried: the sender had no working Gmail connection, or the address is not one. */
    NOT_SENT;

    public boolean retryable() {
        return this == FAILED || this == NOT_SENT;
    }
}
