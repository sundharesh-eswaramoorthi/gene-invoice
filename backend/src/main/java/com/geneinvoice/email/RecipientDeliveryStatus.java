package com.geneinvoice.email;

public enum RecipientDeliveryStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    BOUNCED,
    FAILED,
    NOT_SENT
}
