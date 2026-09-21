package com.geneinvoice.mail.message;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectionService;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.gmail.GmailApiException;
import com.geneinvoice.mail.gmail.GmailClient;
import com.geneinvoice.mail.gmail.GmailMime;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import com.geneinvoice.mail.gmail.GoogleTokens;
import com.geneinvoice.mail.gmail.MailAddress;
import com.geneinvoice.mail.tracking.MailboxSync;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class SendWorker {

    static final String NO_ID = "Gmail accepted the message but did not return its id";
    static final String CHECK_FAILED = "Could not check whether the email was already sent: ";
    private static final Set<MessageStatus> SETTLED = EnumSet.of(MessageStatus.SENT, MessageStatus.DELIVERED,
            MessageStatus.READ, MessageStatus.BOUNCED);

    private final MailMessageRepository messages;
    private final MessageService messageService;
    private final ConnectionService connections;
    private final GoogleTokens tokens;
    private final GmailClient gmail;
    private final MailboxThrottle throttle;
    private final MailboxSync mailboxSync;
    private final SendQueue queue;
    private final MailProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SendWorker(MailMessageRepository messages, MessageService messageService, ConnectionService connections,
                      GoogleTokens tokens, GmailClient gmail, MailboxThrottle throttle, MailboxSync mailboxSync,
                      SendQueue queue, MailProperties properties, TransactionTemplate transactions, Clock clock) {
        this.messages = messages;
        this.messageService = messageService;
        this.connections = connections;
        this.tokens = tokens;
        this.gmail = gmail;
        this.throttle = throttle;
        this.mailboxSync = mailboxSync;
        this.queue = queue;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    private record Sent(String providerMessageId, String providerThreadId, String rfcMessageId, String fromAddress) {}

    public void process(long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A copy must be sent outside a transaction");
        }
        MailMessage copy = transactions.execute(status -> claim(id));
        if (copy == null) return;
        try {
            send(copy);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transactions.executeWithoutResult(status -> unclaim(id));
        } catch (RuntimeException e) {
            log.warn("Copy {} could not be sent", copy.getExternalId(), e);
            failed(id, e.getMessage() == null ? "Delivery failed" : "Delivery failed: " + e.getMessage(),
                    false, true, null);
        }
    }

    private MailMessage claim(long id) {
        if (messages.claim(id, clock.instant()) == 0) return null;
        MailMessage copy = messages.findById(id).orElseThrow();
        messageService.recordState(copy);
        return copy;
    }

    private void send(MailMessage copy) throws InterruptedException {
        long id = copy.getId();
        MailConnection sender = connections.byOwner(copy.getSenderRef()).orElse(null);
        if (sender == null || sender.getStatus() == ConnectionStatus.DISCONNECTED) {
            notSent(id, MessageService.notConnected(copy.getFromName()), sender);
            return;
        }
        if (sender.getStatus() == ConnectionStatus.NEEDS_RECONNECT) {
            notSent(id, MessageService.needsRenewing(copy.getFromName()), sender);
            return;
        }

        throttle.acquire(sender.getId());

        try {
            try {
                tokens.accessToken(sender);
            } catch (GmailApiException e) {
                failed(id, e.getMessage(), e.isTransientFailure(), false, sender);
                return;
            }

            if (copy.isDeliveryUncertain()) {
                GmailClient.MessageRef earlier;
                try {
                    earlier = findSent(sender, copy.getRfcMessageId());
                } catch (GmailApiException e) {
                    failed(id, CHECK_FAILED + e.getMessage(), e.isTransientFailure(), false, sender);
                    return;
                }
                if (earlier != null) {
                    log.info("Copy {} was already in {}'s Sent mail; not sending it again",
                            copy.getExternalId(), sender.getOwnerRef());
                    Sent found = readBack(sender, earlier.id(), earlier.threadId(), copy);
                    if (sent(id, sender, found)) mailboxSync.threadRecorded(sender, found.providerThreadId(), found.providerMessageId(), null);
                    return;
                }
            }

            byte[] mime;
            try {
                mime = GmailMime.build(new MailAddress(copy.getFromName(), sender.getGmailAddress()),
                        new MailAddress(copy.getToName(), copy.getToAddress()), copy.getSubject(), copy.getBody(),
                        copy.getRfcMessageId(), clock.instant());
            } catch (MessagingException e) {
                failed(id, e.getMessage(), false, false, sender);
                return;
            }
            GmailClient.SendResult result;
            long readingMark = mailboxSync.readingMark(sender.getId());
            try {
                result = gmail.send(sender, mime);
            } catch (GmailApiException e) {
                failed(id, e.getMessage(), e.isTransientFailure(), e.isOutcomeUnknown(), sender);
                return;
            }
            if (result == null || result.id() == null) {
                failed(id, NO_ID, false, true, sender);
                return;
            }
            Sent went = readBack(sender, result.id(), result.threadId(), copy);
            if (sent(id, sender, went)) mailboxSync.threadRecorded(sender, went.providerThreadId(), went.providerMessageId(), readingMark);
        } catch (GoogleAuthException e) {
            connections.needsReconnect(sender, e);
            notSent(id, MessageService.needsRenewing(copy.getFromName()), sender);
        }
    }

    private GmailClient.MessageRef findSent(MailConnection sender, String messageId) {
        String bare = messageId.startsWith("<") && messageId.endsWith(">")
                ? messageId.substring(1, messageId.length() - 1) : messageId;
        GmailClient.MessagePage found = gmail.listMessages(sender, "rfc822msgid:" + bare, null, true);
        return found == null || found.messages() == null ? null
                : found.messages().stream().filter(m -> m.id() != null).findFirst().orElse(null);
    }

    private Sent readBack(MailConnection sender, String providerMessageId, String threadId, MailMessage copy) {
        String messageId = copy.getRfcMessageId();
        String from = sender.getGmailAddress();
        try {
            Map<String, String> headers = gmail.headers(sender, providerMessageId, List.of("Message-ID", "From"));
            String gmailMessageId = headers.get("message-id");
            if (gmailMessageId != null && gmailMessageId.length() <= MailMessage.HEADER_ID_MAX) messageId = gmailMessageId;
            String gmailFrom = GmailMime.address(headers.get("from"));
            if (gmailFrom != null && gmailFrom.length() <= MailMessage.ADDRESS_MAX) from = gmailFrom;
        } catch (RuntimeException e) {
            log.debug("Could not read back the headers of Gmail message {}: {}", providerMessageId, e.getMessage());
        }
        return new Sent(providerMessageId, threadId, messageId, from);
    }

    private boolean sent(long id, MailConnection sender, Sent sent) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            MailMessage m = messageService.locked(id);
            if (m == null || SETTLED.contains(m.getStatus())) return false;
            if (m.getStatus() != MessageStatus.SENDING) {
                log.info("Copy {} was sent after it had been marked {}", m.getExternalId(), m.getStatus());
            }
            m.setStatus(MessageStatus.SENT);
            m.setSentAt(clock.instant());
            m.setConnectionId(sender.getId());
            m.setFromAddress(sent.fromAddress());
            m.setProviderMessageId(sent.providerMessageId());
            m.setProviderThreadId(sent.providerThreadId());
            m.setRfcMessageId(sent.rfcMessageId());
            m.setError(null);
            m.setNextAttemptAt(null);
            m.setDeliveryUncertain(false);
            messageService.changed(m);
            return true;
        }));
    }

    private void notSent(long id, String reason, MailConnection sender) {
        transactions.executeWithoutResult(status -> {
            MailMessage m = messageService.locked(id);
            if (m == null || m.getStatus() != MessageStatus.SENDING) return;
            m.setStatus(MessageStatus.NOT_SENT);
            m.setError(reason);
            m.setNextAttemptAt(null);
            if (sender != null) m.setConnectionId(sender.getId());
            messageService.changed(m);
        });
    }

    private void failed(long id, String message, boolean transientFailure, boolean deliveryUncertain,
                        MailConnection sender) {
        Duration retryIn = transactions.execute(status -> {
            MailMessage m = messageService.locked(id);
            if (m == null || m.getStatus() != MessageStatus.SENDING) return null;
            m.setError(MailText.fit(message == null ? "Delivery failed" : message, MailMessage.ERROR_MAX));
            if (deliveryUncertain) m.setDeliveryUncertain(true);
            if (sender != null) m.setConnectionId(sender.getId());
            Duration delay = null;
            if (transientFailure && m.getAttempts() < properties.getSend().getMaxAttempts()) {
                delay = properties.getSend().retryDelay(m.getAttempts());
                Instant due = clock.instant().plus(delay);
                m.setStatus(MessageStatus.QUEUED);
                m.setNextAttemptAt(due);
                m.setEnqueuedAt(due);
            } else {
                m.setStatus(MessageStatus.FAILED);
                m.setNextAttemptAt(null);
            }
            messageService.changed(m);
            return delay;
        });
        if (retryIn == null) return;
        try {
            queue.enqueueRetry(id, retryIn);
        } catch (RuntimeException e) {
            log.warn("Could not put copy {} on the retry queue; the sweeper will: {}", id, e.getMessage());
        }
    }

    private void unclaim(long id) {
        MailMessage m = messageService.locked(id);
        if (m == null || m.getStatus() != MessageStatus.SENDING) return;
        m.setStatus(MessageStatus.QUEUED);
        m.setAttempts(Math.max(0, m.getAttempts() - 1));
        m.setEnqueuedAt(null);
        messageService.changed(m);
    }
}
