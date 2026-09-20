package com.geneinvoice.email.transport;

/**
 * What the mail service knows about a received reply besides the message itself.
 *
 * @param mailboxOwnerUserId the user whose connected Gmail received it: the sender of the email it answers (E8)
 * @param mailboxAddress     that Gmail address; To and Cc entries naming it stand for its owner
 * @param repliedToExternalId the copy it answers, {@code gi-{emailId}-{recipientId}}, or null
 */
public record InboundHint(long mailboxOwnerUserId, String mailboxAddress, String repliedToExternalId) {}
