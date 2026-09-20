package com.geneinvoice.email;

/**
 * Where one recipient's copy of an outbound email stands, as the mail service reports it (M8). Every
 * To recipient with an address gets a copy of their own, which moves forward only:
 * {@code QUEUED → SENDING → SENT → DELIVERED → READ}, or ends {@code BOUNCED}, {@code FAILED} or
 * {@code NOT_SENT}.
 */
public enum RecipientDeliveryStatus {
    QUEUED,
    SENDING,
    /** Gmail accepted it. */
    SENT,
    /** Seen in the recipient's own Gmail (confirmed), or no bounce came back in time (estimated). */
    DELIVERED,
    /** The recipient's connected Gmail shows it read. */
    READ,
    /** A delivery-failure notice came back. */
    BOUNCED,
    FAILED,
    /** Never handed to Gmail: delivery is not set up, or the sender has no working Gmail connection. */
    NOT_SENT
}
