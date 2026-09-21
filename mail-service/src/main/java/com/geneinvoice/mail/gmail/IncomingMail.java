package com.geneinvoice.mail.gmail;

import java.time.Instant;
import java.util.List;

public record IncomingMail(String providerMessageId, String providerThreadId, String rfcMessageId, String inReplyTo,
                           List<String> references, MailAddress from, List<MailAddress> to, List<MailAddress> cc,
                           String subject, String body, Instant receivedAt) {}
