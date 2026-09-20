package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.gmail.IncomingMail;
import com.geneinvoice.mail.gmail.MailAddress;
import com.geneinvoice.mail.message.MailMessage;

import java.time.Instant;
import java.util.List;

/**
 * The data of a {@code message.received} event (§4.8): a reply that arrived in a mailbox, in a thread
 * a copy was sent from, and the copy it answers. The body is cut as the backend stores it.
 */
public record ReceivedMail(String ownerRef, String mailboxAddress, String repliedToExternalId,
                           String providerMessageId, String providerThreadId, String rfcMessageId, String inReplyTo,
                           List<String> references, MailAddress from, List<MailAddress> to, List<MailAddress> cc,
                           String subject, String body, Instant receivedAt) {

    static ReceivedMail of(MailConnection mailbox, MailMessage repliedTo, IncomingMail mail) {
        return new ReceivedMail(mailbox.getOwnerRef(), mailbox.getGmailAddress(), repliedTo.getExternalId(),
                mail.providerMessageId(), mail.providerThreadId(), mail.rfcMessageId(), mail.inReplyTo(),
                mail.references(), mail.from(), mail.to(), mail.cc(), mail.subject(),
                MailText.fit(mail.body(), MailMessage.BODY_MAX), mail.receivedAt());
    }
}
