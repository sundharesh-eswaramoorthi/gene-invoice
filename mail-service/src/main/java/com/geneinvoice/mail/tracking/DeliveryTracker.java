package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectionService;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import com.geneinvoice.mail.gmail.GmailApiException;
import com.geneinvoice.mail.gmail.GmailClient;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import com.geneinvoice.mail.message.MailMessage;
import com.geneinvoice.mail.message.MailMessageRepository;
import com.geneinvoice.mail.message.MessageService;
import com.geneinvoice.mail.message.MessageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Delivered, as far as Gmail lets anyone know (§4.6, M8). Gmail gives no receipts, so a copy to
 * someone whose own Gmail is connected is looked for in their mailbox (confirmed, and read when it is
 * no longer unread); any other copy counts as delivered once no bounce came back for a while
 * (estimated, and a bounce that comes later still wins).
 */
@Component
@Slf4j
public class DeliveryTracker {

    static final int CONFIRM_BATCH = 200;
    static final int ESTIMATE_BATCH = 500;

    private final MailProperties properties;
    private final MailMessageRepository messages;
    private final MailConnectionRepository connections;
    private final ConnectionService connectionService;
    private final MessageService messageService;
    private final GmailClient gmail;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DeliveryTracker(MailProperties properties, MailMessageRepository messages, MailConnectionRepository connections,
                           ConnectionService connectionService, MessageService messageService, GmailClient gmail,
                           TransactionTemplate transactions, Clock clock) {
        this.properties = properties;
        this.messages = messages;
        this.connections = connections;
        this.connectionService = connectionService;
        this.messageService = messageService;
        this.gmail = gmail;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mail.tracking.interval-ms:60000}",
            initialDelayString = "${mail.tracking.interval-ms:60000}")
    public void run() {
        try {
            track();
        } catch (RuntimeException e) {
            log.warn("Delivery tracking failed", e);
        }
    }

    public void track() {
        confirm();
        estimate();
    }

    /** Step 1: copies to a connected mailbox, looked for in it by their Message-ID. */
    void confirm() {
        Instant sentSince = clock.instant().minus(properties.getTracking().getConfirmWindow());
        List<MailMessage> candidates = transactions.execute(status ->
                messages.findToConfirm(sentSince, PageRequest.of(0, CONFIRM_BATCH)));
        if (candidates == null || candidates.isEmpty()) return;
        Map<String, Optional<MailConnection>> mailboxes = new HashMap<>();
        Set<Long> refused = new HashSet<>();
        for (MailMessage copy : candidates) {
            MailConnection mailbox = mailboxes.computeIfAbsent(copy.getToAddressKey(), address ->
                    transactions.execute(status -> connections.findFirstByGmailAddressAndStatusOrderByIdAsc(
                            address, ConnectionStatus.CONNECTED))).orElse(null);
            if (mailbox == null || refused.contains(mailbox.getId())) continue;
            GmailClient.MessageRef found;
            List<String> labels;
            try {
                found = find(mailbox, copy.getRfcMessageId());
                if (found == null) continue;
                GmailClient.MessageInfo info = gmail.messageInfo(mailbox, found.id());
                labels = info == null ? null : info.labelIds();
            } catch (GoogleAuthException e) {
                connectionService.needsReconnect(mailbox, e);
                refused.add(mailbox.getId());
                continue;
            } catch (GmailApiException e) {
                log.debug("Could not look for copy {} in the mailbox of {}: {}", copy.getExternalId(),
                        mailbox.getOwnerRef(), e.getMessage());
                continue;
            }
            transactions.executeWithoutResult(status -> confirmed(copy.getId(), mailbox.getId(), found.id(), labels));
        }
    }

    private GmailClient.MessageRef find(MailConnection mailbox, String rfcMessageId) {
        String bare = rfcMessageId.startsWith("<") && rfcMessageId.endsWith(">")
                ? rfcMessageId.substring(1, rfcMessageId.length() - 1) : rfcMessageId;
        GmailClient.MessagePage page = gmail.listMessages(mailbox, "rfc822msgid:" + bare, null, true);
        return page == null || page.messages() == null ? null
                : page.messages().stream().filter(m -> m.id() != null).findFirst().orElse(null);
    }

    private void confirmed(Long id, Long mailboxId, String recipientMessageId, List<String> labels) {
        MailMessage copy = messageService.locked(id);
        if (copy == null || copy.getRecipientMessageId() != null
                || (copy.getStatus() != MessageStatus.SENT && copy.getStatus() != MessageStatus.DELIVERED)) {
            return;
        }
        Instant now = clock.instant();
        copy.setRecipientConnectionId(mailboxId);
        copy.setRecipientMessageId(recipientMessageId);
        copy.setDeliveredConfirmed(true);
        copy.setStatus(MessageStatus.DELIVERED);
        if (copy.getDeliveredAt() == null) copy.setDeliveredAt(now);
        if (labels == null || !labels.contains(MailboxSync.UNREAD)) {
            copy.setStatus(MessageStatus.READ);
            copy.setReadAt(now);
        }
        messageService.changed(copy);
    }

    /** Step 2: no bounce within {@code delivered-after}: delivered, by estimate. */
    void estimate() {
        Instant now = clock.instant();
        Instant sentBefore = now.minus(properties.getTracking().getDeliveredAfter());
        List<Long> ids = transactions.execute(status ->
                messages.findToEstimate(sentBefore, PageRequest.of(0, ESTIMATE_BATCH)));
        for (Long id : ids == null ? List.<Long>of() : ids) {
            transactions.executeWithoutResult(status -> {
                MailMessage copy = messageService.locked(id);
                if (copy == null || copy.getStatus() != MessageStatus.SENT || !copy.getSentAt().isBefore(sentBefore)) {
                    return;
                }
                copy.setStatus(MessageStatus.DELIVERED);
                copy.setDeliveredConfirmed(false);
                copy.setDeliveredAt(clock.instant());
                messageService.changed(copy);
            });
        }
    }
}
