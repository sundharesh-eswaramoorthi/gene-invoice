package com.geneinvoice.mail.gmail;

import java.time.Instant;
import java.util.List;

/**
 * A received message as read from Gmail. Header ids keep their angle brackets; no text holds a NUL.
 *
 * @param receivedAt when Gmail received it, else the Date header, else when it was read
 */
public record IncomingMail(String providerMessageId, String providerThreadId, String rfcMessageId, String inReplyTo,
                           List<String> references, MailAddress from, List<MailAddress> to, List<MailAddress> cc,
                           String subject, String body, Instant receivedAt) {}
