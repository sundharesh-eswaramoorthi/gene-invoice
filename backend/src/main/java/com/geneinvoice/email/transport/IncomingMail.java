package com.geneinvoice.email.transport;

import java.time.Instant;
import java.util.List;

/**
 * A reply the mail service found in a connected mailbox, already parsed.
 *
 * @param providerMessageId the provider's id for the message
 * @param providerThreadId  the provider's conversation id
 * @param rfcMessageId      its Message-ID header, or null
 * @param inReplyTo         its In-Reply-To header, or null
 * @param references        the Message-IDs in its References header, oldest first; never null
 * @param from              the sender as written in the header
 * @param to                the To addresses; never null
 * @param cc                the Cc addresses; never null
 * @param subject           decoded subject, possibly empty
 * @param body              plain-text body (HTML-only mail reduced to text), possibly empty
 * @param receivedAt        when the provider received it
 */
public record IncomingMail(String providerMessageId, String providerThreadId, String rfcMessageId,
                           String inReplyTo, List<String> references,
                           MailAddress from, List<MailAddress> to, List<MailAddress> cc,
                           String subject, String body, Instant receivedAt) {}
