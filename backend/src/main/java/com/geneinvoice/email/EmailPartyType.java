package com.geneinvoice.email;

/** Who an email is from, or one entry in its To line. */
public enum EmailPartyType {
    /** An internal user, picked by name. */
    USER,
    /** A role: as a sender it shows the role's mailbox, as a recipient everyone in it at send time. */
    ROLE,
    /** One of the customer's own addresses. Recorded on the email; it has no inbox. */
    CUSTOMER_EMAIL
}
