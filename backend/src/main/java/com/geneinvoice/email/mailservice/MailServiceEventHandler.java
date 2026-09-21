package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.CopyRef;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDeliveryRollup;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.EmailRecipientRepository;
import com.geneinvoice.email.EmailRepository;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.email.RecipientDeliveryStatus;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.mailservice.MailServiceDtos.Event;
import com.geneinvoice.email.mailservice.MailServiceDtos.MessageReceived;
import com.geneinvoice.email.mailservice.MailServiceDtos.Party;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.InboundHint;
import com.geneinvoice.email.transport.IncomingMail;
import com.geneinvoice.email.transport.IncomingMailHandler;
import com.geneinvoice.email.transport.MailAddress;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "mail-service")
@RequiredArgsConstructor
@Slf4j
public class MailServiceEventHandler {

    static final String RECONNECT_TYPE = "GMAIL_RECONNECT";
    static final String RECONNECT_TITLE = "Reconnect your Gmail";
    static final String RECONNECT_LINK = "/me/gmail";
    private static final String RECONNECT_FALLBACK = "Your Gmail connection needs to be renewed.";

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final IncomingMailHandler inbound;
    private final GmailConnectionService gmailConnections;
    private final NotificationService notifications;
    private final UserRepository userRepository;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public void apply(Event event) {
        switch (event.type() == null ? "" : event.type()) {
            case "message.status" -> copyChanged(data(event, CopyState.class));
            case "message.received" -> replyReceived(data(event, MessageReceived.class));
            case "connection.status" -> connectionChanged(data(event, ConnectionState.class));
            default -> log.debug("Ignoring mail service event {} of type {}", event.id(), event.type());
        }
    }

    public static boolean databaseUnavailable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof TransactionException || t instanceof org.hibernate.TransactionException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof SQLNonTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    private <T> T data(Event event, Class<T> type) {
        JsonNode data = event.data();
        if (data == null || data.isNull()) throw new IllegalArgumentException("The event has no data");
        try {
            return json.treeToValue(data, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The event's data cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private void copyChanged(CopyState state) {
        Optional<CopyRef> ref = CopyRef.parse(state.externalId());
        if (ref.isEmpty()) {
            log.debug("Ignoring a report on copy {}, which is not one of the app's", state.externalId());
            return;
        }
        long emailId = ref.get().emailId();
        long recipientId = ref.get().recipientId();
        transactions.executeWithoutResult(tx -> {
            Email email = emailRepository.findByIdForUpdate(emailId)
                    .filter(e -> e.getDirection() == EmailDirection.OUTBOUND).orElse(null);
            List<EmailRecipient> recipients = email == null ? List.of()
                    : recipientRepository.findByEmailIdOrderByIdAsc(emailId);
            EmailRecipient copy = recipients.stream().filter(r -> r.getId() == recipientId).findFirst().orElse(null);
            if (copy == null) {
                log.debug("Ignoring a report on copy {}: no such recipient on an outbound email", state.externalId());
                return;
            }
            boolean awaitingHandOff = copy.getDeliveryStatus() == RecipientDeliveryStatus.QUEUED;
            boolean handOffUnderWay = email.getStatus() == EmailStatus.SENDING && email.getHandedOffAt() == null;
            if (!EmailDeliveryRollup.applyCopy(email, copy, state)) return;
            if (awaitingHandOff) EmailDeliveryRollup.handedOff(email, Instant.now());
            EmailDeliveryRollup.apply(email, recipients);
            if (handOffUnderWay && email.getHandedOffAt() == null && email.getStatus() == EmailStatus.QUEUED) {
                email.setStatus(EmailStatus.SENDING);
            }
            recipientRepository.save(copy);
            emailRepository.save(email);
        });
    }

    private void replyReceived(MessageReceived m) {
        Long owner = userId(m.ownerRef());
        if (owner == null) {
            log.debug("Ignoring a reply for mailbox owner {}, who is not a user", m.ownerRef());
            return;
        }
        IncomingMail mail = new IncomingMail(m.providerMessageId(), m.providerThreadId(), m.rfcMessageId(),
                m.inReplyTo(), m.references() == null ? List.of() : m.references(),
                address(m.from()), addresses(m.to()), addresses(m.cc()),
                m.subject(), m.body(), m.receivedAt());
        inbound.handle(mail, new InboundHint(owner, m.mailboxAddress(), m.repliedToExternalId()));
    }

    private static MailAddress address(Party party) {
        return party == null ? null : new MailAddress(party.name(), party.address());
    }

    private static List<MailAddress> addresses(List<Party> parties) {
        return parties == null ? List.of()
                : parties.stream().filter(Objects::nonNull).map(MailServiceEventHandler::address).toList();
    }

    private void connectionChanged(ConnectionState state) {
        Long userId = userId(state.ownerRef());
        if (userId == null) {
            log.debug("Ignoring a connection report for owner {}, who is not a user", state.ownerRef());
            return;
        }
        transactions.executeWithoutResult(tx -> {
            if (!userRepository.existsById(userId)) {
                log.debug("Ignoring a connection report for user {}, who no longer exists", userId);
                return;
            }
            ConnectionStatus before = gmailConnections.mirror(userId, state);
            if (state.status() == ConnectionStatus.NEEDS_RECONNECT && before != ConnectionStatus.NEEDS_RECONNECT) {
                String reason = state.statusReason() == null || state.statusReason().isBlank()
                        ? RECONNECT_FALLBACK : state.statusReason();
                notifications.notify(userId, RECONNECT_TYPE, RECONNECT_TITLE, reason, RECONNECT_LINK);
            }
        });
    }

    private static Long userId(String ownerRef) {
        if (ownerRef == null || !ownerRef.matches("\\d{1,18}")) return null;
        return Long.parseLong(ownerRef);
    }
}
