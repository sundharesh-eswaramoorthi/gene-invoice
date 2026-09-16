package com.geneinvoice.email;

/**
 * How one recipient occurrence came to be on an Email. DIRECT names an internal user selected
 * by the sender; ROLE records send-time membership of a selected role and keeps the role's name;
 * CUSTOMER_ADDRESS is the record's Customer email address and never joins an internal user's
 * Inbox (FR18).
 */
public enum EmailRecipientKind {
    DIRECT,
    ROLE,
    CUSTOMER_ADDRESS
}
