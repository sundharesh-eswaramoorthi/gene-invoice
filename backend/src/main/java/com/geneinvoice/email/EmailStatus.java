package com.geneinvoice.email;

/**
 * Where an email stands. Outbound mail is saved {@code QUEUED} and handed to the mail service from
 * there; once handed over, its status is the roll-up of its recipients' copies (M12). Received mail
 * is {@code RECEIVED} from the start.
 */
public enum EmailStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    /** Saved in the app but never sent: delivery is not set up, nobody has an address, or the sender has no Gmail. */
    NOT_SENT,
    RECEIVED,
    /** Some copies went out and some did not ("Partly sent"). */
    PARTIAL
}
