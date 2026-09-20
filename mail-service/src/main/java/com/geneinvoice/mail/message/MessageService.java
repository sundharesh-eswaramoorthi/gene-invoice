package com.geneinvoice.mail.message;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.ApiException;
import com.geneinvoice.mail.config.InvalidFieldsException;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import com.geneinvoice.mail.events.EventRecorder;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Takes copies from the backend (§4.3) and keeps every change to a copy together with its event.
 * A submit is one transaction; the queue hears of the new copies only once it has committed, so a
 * worker never looks for a row that is not there yet.
 */
@Service
@Slf4j
public class MessageService {

    static final int MAX_COPIES = 500;
    static final String DEFAULT_DOMAIN = "geneinvoice.local";

    private final MailMessageRepository messages;
    private final MailConnectionRepository connections;
    private final EventRecorder events;
    private final SendQueue queue;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final Clock clock;

    public MessageService(MailMessageRepository messages, MailConnectionRepository connections, EventRecorder events,
                          SendQueue queue, TransactionTemplate transactions, EntityManager entityManager, Clock clock) {
        this.messages = messages;
        this.connections = connections;
        this.events = events;
        this.queue = queue;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** A submit after validation: every text storable, trimmed and cut as §4.3 says. */
    private record Submission(String senderRef, String senderName, String subject, String body, String groupRef,
                              boolean retry, List<CopyInput> copies) {}

    private record CopyInput(String externalId, String toName, String toAddress) {}

    private record Saved(List<CopyState> states, List<Long> queued) {}

    /**
     * Saves the copies and queues those that can go. A copy the service already has is returned as it
     * stands — handing the same email over again never sends it twice — unless {@code retry} asks for
     * a failed or unsent one to be sent again.
     */
    public List<CopyState> submit(SubmitRequest request) {
        Submission submission = validated(request);
        Saved saved;
        try {
            saved = transactions.execute(status -> save(submission));
        } catch (DataIntegrityViolationException e) {
            // Another request saved some of the same copies at the same moment. They exist now, and the
            // second pass returns them as they stand.
            log.info("Copies of group {} were submitted twice at once; reading them back", submission.groupRef());
            saved = transactions.execute(status -> save(submission));
        }
        publish(saved.queued());
        return saved.states();
    }

    public CopyState get(String externalId) {
        return transactions.execute(status -> messages.findByExternalId(externalId).map(CopyState::of))
                .orElseThrow(() -> ApiException.notFound("No message " + externalId));
    }

    /**
     * The copy, locked until the transaction ends and read afresh under the lock, so two changes to one
     * copy never overwrite each other — also when the transaction read it before. Null when it is gone.
     */
    public MailMessage locked(Long id) {
        MailMessage message = entityManager.find(MailMessage.class, id);
        if (message == null) return null;
        entityManager.refresh(message, LockModeType.PESSIMISTIC_WRITE);
        return message;
    }

    /**
     * Records a change to a copy the client can see: {@code seq} up by one, the row saved and a
     * {@code message.status} event, all in the caller's transaction.
     */
    public void changed(MailMessage message) {
        message.setSeq(message.getSeq() + 1);
        message.setUpdatedAt(clock.instant());
        messages.save(message);
        recordState(message);
    }

    /** The event for a copy whose change was written by an update query (the claim). */
    public void recordState(MailMessage message) {
        events.record(EventRecorder.MESSAGE_STATUS, message.getSenderRef(), message.getExternalId(),
                CopyState.of(message));
    }

    /**
     * Puts copies on the queue and notes when. A publish that fails is only logged: the copy stays
     * queued, and the sweeper publishes it again.
     */
    public void publish(List<Long> ids) {
        if (ids.isEmpty()) return;
        List<Long> published = new ArrayList<>();
        for (Long id : ids) {
            try {
                queue.enqueue(id);
                published.add(id);
            } catch (RuntimeException e) {
                log.warn("Could not put copy {} on the send queue; the sweeper will: {}", id, e.getMessage());
            }
        }
        if (!published.isEmpty()) {
            transactions.executeWithoutResult(status -> messages.markEnqueued(published, clock.instant()));
        }
    }

    private Saved save(Submission s) {
        Instant now = clock.instant();
        MailConnection sender = connections.findByOwnerRef(s.senderRef()).orElse(null);
        Map<String, MailMessage> existing = messages.findByExternalIdIn(
                        s.copies().stream().map(CopyInput::externalId).toList()).stream()
                .collect(Collectors.toMap(MailMessage::getExternalId, Function.identity()));
        List<CopyState> states = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (CopyInput copy : s.copies()) {
            MailMessage m = existing.get(copy.externalId());
            if (m != null && !(s.retry() && m.getStatus().retryable())) {
                states.add(CopyState.of(m));
                continue;
            }
            if (m == null) {
                m = MailMessage.builder()
                        .externalId(copy.externalId())
                        .rfcMessageId(newMessageId(sender))
                        .createdAt(now)
                        .seq(0)
                        .build();
            }
            // New, or a failed or unsent copy sent again: as a new copy would be, keeping only its
            // Message-ID and whether an earlier attempt may have gone out after all.
            m.setGroupRef(s.groupRef());
            m.setSenderRef(s.senderRef());
            m.setFromName(s.senderName());
            m.setToName(copy.toName());
            m.setToAddress(copy.toAddress());
            m.setToAddressKey(copy.toAddress().toLowerCase(Locale.ROOT));
            m.setSubject(s.subject());
            m.setBody(s.body());
            m.setConnectionId(sender == null ? null : sender.getId());
            m.setFromAddress(null);
            m.setAttempts(0);
            m.setNextAttemptAt(null);
            m.setEnqueuedAt(null);
            m.setProviderMessageId(null);
            m.setProviderThreadId(null);
            m.setRecipientConnectionId(null);
            m.setRecipientMessageId(null);
            m.setSentAt(null);
            m.setDeliveredAt(null);
            m.setReadAt(null);
            m.setBouncedAt(null);
            m.setDeliveredConfirmed(false);
            String reason = notSendable(sender, s.senderName(), copy.toAddress());
            m.setStatus(reason == null ? MessageStatus.QUEUED : MessageStatus.NOT_SENT);
            m.setError(reason);
            changed(m);
            states.add(CopyState.of(m));
            if (reason == null) queued.add(m.getId());
        }
        return new Saved(states, queued);
    }

    /** Why the copy cannot go (M5), or null when it can. */
    private static String notSendable(MailConnection sender, String senderName, String address) {
        if (sender == null || sender.getStatus() == ConnectionStatus.DISCONNECTED) {
            return notConnected(senderName);
        }
        if (sender.getStatus() == ConnectionStatus.NEEDS_RECONNECT) return needsRenewing(senderName);
        try {
            new InternetAddress(address, true);
        } catch (AddressException e) {
            return MailText.fit("Invalid email address: " + address, MailMessage.ERROR_MAX);
        }
        return null;
    }

    public static String notConnected(String senderName) {
        return MailText.fit(senderName + " has not connected Gmail", MailMessage.ERROR_MAX);
    }

    public static String needsRenewing(String senderName) {
        return MailText.fit(senderName + "'s Gmail connection needs to be renewed", MailMessage.ERROR_MAX);
    }

    /** {@code <gm-uuid@gmail.com>}: the sender's domain, so it looks like the rest of their mail. */
    private static String newMessageId(MailConnection sender) {
        String address = sender == null ? null : sender.getGmailAddress();
        int at = address == null ? -1 : address.lastIndexOf('@');
        String domain = at < 0 || at == address.length() - 1 ? DEFAULT_DOMAIN : address.substring(at + 1);
        return "<gm-" + UUID.randomUUID() + "@" + domain + ">";
    }

    // ---- validation (§4.3) -------------------------------------------------------------

    private static Submission validated(SubmitRequest request) {
        if (request == null) throw ApiException.badRequest("A request body is required");
        Map<String, String> invalid = new LinkedHashMap<>();

        SubmitRequest.Sender sender = request.sender();
        String senderRef = clean(sender == null ? null : sender.ownerRef());
        if (senderRef == null) invalid.put("sender.ownerRef", "Enter the sender");
        else if (senderRef.length() > MailMessage.OWNER_REF_MAX) invalid.put("sender.ownerRef", "The sender reference is too long");
        String senderName = clean(sender == null ? null : sender.name());
        senderName = MailText.fit(senderName == null ? senderRef : senderName, MailMessage.NAME_MAX);

        String subject = MailText.oneLine(MailText.storable(request.subject()));
        if (subject.isEmpty()) invalid.put("subject", "Enter a subject");
        else if (subject.length() > MailMessage.SUBJECT_MAX) invalid.put("subject", "The subject is too long");

        String body = request.body() == null ? "" : MailText.storable(request.body());
        if (body.length() > MailMessage.BODY_MAX) invalid.put("body", "The body is too long");

        String groupRef = clean(request.groupRef());
        if (groupRef != null && groupRef.length() > MailMessage.GROUP_REF_MAX) {
            invalid.put("groupRef", "The group reference is too long");
        }

        List<CopyInput> copies = new ArrayList<>();
        List<SubmitRequest.Copy> given = request.copies() == null ? List.of() : request.copies();
        if (given.isEmpty()) invalid.put("copies", "Add at least one copy");
        else if (given.size() > MAX_COPIES) invalid.put("copies", "At most " + MAX_COPIES + " copies at a time");
        else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < given.size(); i++) {
                SubmitRequest.Copy copy = given.get(i) == null ? new SubmitRequest.Copy(null, null) : given.get(i);
                String externalId = clean(copy.externalId());
                String field = "copies[" + i + "].";
                if (externalId == null) invalid.put(field + "externalId", "Enter the external id");
                else if (externalId.length() > MailMessage.EXTERNAL_ID_MAX) {
                    invalid.put(field + "externalId", "The external id is too long");
                } else if (!seen.add(externalId)) {
                    invalid.put(field + "externalId", "The external id is repeated in this request");
                }
                String address = clean(copy.to() == null ? null : copy.to().address());
                if (address == null) invalid.put(field + "to.address", "Enter the address");
                else if (address.length() > MailMessage.ADDRESS_MAX) invalid.put(field + "to.address", "The address is too long");
                String name = clean(copy.to() == null ? null : copy.to().name());
                copies.add(new CopyInput(externalId, MailText.fit(name == null ? address : name, MailMessage.NAME_MAX), address));
            }
        }
        if (!invalid.isEmpty()) throw new InvalidFieldsException(invalid);
        return new Submission(senderRef, senderName, subject, body, groupRef, Boolean.TRUE.equals(request.retry()),
                copies);
    }

    /** Storable and trimmed; blank is null. */
    private static String clean(String value) {
        String text = MailText.storable(value);
        return text == null || text.isBlank() ? null : text.trim();
    }
}
