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

/**
 * Sends one copy (§4.5). Every database step is its own short transaction and none is open during a
 * Google call: a slow Gmail must never hold a pooled connection. The claim makes sure only one
 * worker sends a copy; a copy an earlier attempt may have sent is looked for in the sender's mailbox
 * before it is sent again. Once a copy is recorded as sent, {@link MailboxSync#threadRecorded} looks
 * at its thread if a mailbox run may have passed over a bounce or reply there before it was known.
 */
@Component
@Slf4j
public class SendWorker {

    static final String NO_ID = "Gmail accepted the message but did not return its id";
    static final String CHECK_FAILED = "Could not check whether the email was already sent: ";
    /** A copy already past sending: a late success has nothing to add. */
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

    /** What went out, as Gmail recorded it. */
    private record Sent(String providerMessageId, String providerThreadId, String rfcMessageId, String fromAddress) {}

    /**
     * Sends the copy unless another worker has it, it is no longer queued, or its retry is not due;
     * leaves it SENT, NOT_SENT, FAILED, or QUEUED for a retry.
     */
    public void process(long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A copy must be sent outside a transaction");
        }
        MailMessage copy = transactions.execute(status -> claim(id));
        if (copy == null) return;
        try {
            send(copy);
        } catch (InterruptedException e) {
            // Shutting down before anything went to Gmail: back in the queue for the sweeper.
            Thread.currentThread().interrupt();
            transactions.executeWithoutResult(status -> unclaim(id));
        } catch (RuntimeException e) {
            // A fault of our own will not cure itself on a retry. It may have come after Gmail took the
            // message (recording it failed, say), so a retry by hand looks in Sent mail first.
            log.warn("Copy {} could not be sent", copy.getExternalId(), e);
            failed(id, e.getMessage() == null ? "Delivery failed" : "Delivery failed: " + e.getMessage(),
                    false, true, null);
        }
    }

    /** Step 1: SENDING, one more attempt; nothing when someone else has it or its wait is not over. */
    private MailMessage claim(long id) {
        if (messages.claim(id, clock.instant()) == 0) return null;
        MailMessage copy = messages.findById(id).orElseThrow();
        messageService.recordState(copy);
        return copy;
    }

    private void send(MailMessage copy) throws InterruptedException {
        long id = copy.getId();
        // Step 2: the sender's connection as it is now.
        MailConnection sender = connections.byOwner(copy.getSenderRef()).orElse(null);
        if (sender == null || sender.getStatus() == ConnectionStatus.DISCONNECTED) {
            notSent(id, MessageService.notConnected(copy.getFromName()), sender);
            return;
        }
        if (sender.getStatus() == ConnectionStatus.NEEDS_RECONNECT) {
            notSent(id, MessageService.needsRenewing(copy.getFromName()), sender);
            return;
        }

        // Step 3: Gmail's per-mailbox rate.
        throttle.acquire(sender.getId());

        try {
            // Step 4: a token first, so a sign-in failure is never mistaken for a send that may have gone.
            try {
                tokens.accessToken(sender);
            } catch (GmailApiException e) {
                failed(id, e.getMessage(), e.isTransientFailure(), false, sender);
                return;
            }

            // Step 5: an earlier attempt may have delivered it.
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
                    // It went out at some earlier time, and runs have read the mailbox since without
                    // knowing its thread: whatever came in it (a bounce, a quick reply) is looked at now.
                    if (sent(id, sender, found)) mailboxSync.threadRecorded(sender, found.providerThreadId(), found.providerMessageId(), null);
                    return;
                }
            }

            // Step 6: the message itself.
            byte[] mime;
            try {
                mime = GmailMime.build(new MailAddress(copy.getFromName(), sender.getGmailAddress()),
                        new MailAddress(copy.getToName(), copy.getToAddress()), copy.getSubject(), copy.getBody(),
                        copy.getRfcMessageId(), clock.instant());
            } catch (MessagingException e) {
                // A bad address or the like: the same message fails the same way every time.
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
                // Gmail answered 2xx, so it may be on its way: not retried, and a retry by hand looks first.
                failed(id, NO_ID, false, true, sender);
                return;
            }
            Sent went = readBack(sender, result.id(), result.threadId(), copy);
            // A run that read the mailbox between the send and now passed over anything in the thread.
            if (sent(id, sender, went)) mailboxSync.threadRecorded(sender, went.providerThreadId(), went.providerMessageId(), readingMark);
        } catch (GoogleAuthException e) {
            connections.needsReconnect(sender, e);
            notSent(id, MessageService.needsRenewing(copy.getFromName()), sender);
        }
    }

    /**
     * The sender's copy of a message an earlier attempt may have sent, found by our Message-ID, or
     * null when there is none. Not being able to look fails the attempt: sending blind could deliver
     * it twice.
     */
    private GmailClient.MessageRef findSent(MailConnection sender, String messageId) {
        String bare = messageId.startsWith("<") && messageId.endsWith(">")
                ? messageId.substring(1, messageId.length() - 1) : messageId;
        GmailClient.MessagePage found = gmail.listMessages(sender, "rfc822msgid:" + bare, null, true);
        return found == null || found.messages() == null ? null
                : found.messages().stream().filter(m -> m.id() != null).findFirst().orElse(null);
    }

    /**
     * What the sent copy actually carries. Gmail normally keeps our Message-ID but may put its own, and
     * a reply or a bounce quotes whichever went out. The copy has gone either way, so a failed lookup
     * keeps what the service wrote rather than failing the send.
     */
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

    /**
     * Step 7. Also after the sweeper gave up on the attempt: it did go out after all.
     *
     * @return whether the copy was recorded as sent now (not already past sending)
     */
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

    /**
     * Step 8. A transient failure is tried again after the first retry delay, then the second, until
     * {@code max-attempts}; any other failure is final. One that may have gone out anyway makes every
     * later attempt look first — including after a later failure, which does not settle it. A failure
     * reported after the sweeper gave up on the attempt changes nothing.
     */
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
                // It comes off the delay queue then; the sweeper steps in only if it does not.
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

    /** Back to the queue without an attempt used: nothing was sent. */
    private void unclaim(long id) {
        MailMessage m = messageService.locked(id);
        if (m == null || m.getStatus() != MessageStatus.SENDING) return;
        m.setStatus(MessageStatus.QUEUED);
        m.setAttempts(Math.max(0, m.getAttempts() - 1));
        m.setEnqueuedAt(null);
        messageService.changed(m);
    }
}
