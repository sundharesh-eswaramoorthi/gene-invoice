package com.geneinvoice.mail.message;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.ApiException;
import com.geneinvoice.mail.config.InvalidFieldsException;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import com.geneinvoice.mail.events.EventRecorder;
import com.geneinvoice.mail.gmail.Attachment;
import com.geneinvoice.mail.gmail.GmailMime;
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
import java.util.Base64;
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
    private final AttachmentSets attachmentSets;
    private final MailProperties properties;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final Clock clock;

    public MessageService(MailMessageRepository messages, MailConnectionRepository connections, EventRecorder events,
                          SendQueue queue, AttachmentSets attachmentSets, MailProperties properties,
                          TransactionTemplate transactions, EntityManager entityManager, Clock clock) {
        this.messages = messages;
        this.connections = connections;
        this.events = events;
        this.queue = queue;
        this.attachmentSets = attachmentSets;
        this.properties = properties;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** A submit after validation: every text storable, trimmed and cut as §4.3 says, the files decoded. */
    private record Submission(String senderRef, String senderName, String subject, String body, String groupRef,
                              boolean retry, List<CopyInput> copies, List<Attachment> attachments) {}

    private record CopyInput(String externalId, String toName, String toAddress) {}

    private record Saved(List<CopyState> states, List<Long> queued) {}

    /**
     * Saves the copies and queues those that can go. A copy the service already has is returned as it
     * stands — handing the same email over again never sends it twice — unless {@code retry} asks for
     * a failed or unsent one to be sent again.
     */
    public List<CopyState> submit(SubmitRequest request) {
        Submission submission = validated(request, properties.getSend());
        Saved saved;
        try {
            saved = transactions.execute(status -> save(submission));
        } catch (DataIntegrityViolationException e) {
            // Another request saved some of the same copies, or stored the same files, at the same
            // moment. Both exist now, and the second pass reads them back and returns them as they stand.
            log.info("Group {} was submitted twice at once; saving it again reads back what the other saved",
                    submission.groupRef());
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
        // The files once for the whole email, before the copies that point at them (§4.3).
        Long attachmentSetId = attachmentSets.store(s.attachments());
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
            m.setAttachmentSetId(attachmentSetId);
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

    private static Submission validated(SubmitRequest request, MailProperties.Send send) {
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
        List<Attachment> attachments = attachments(request.attachments(), send, invalid);

        if (!invalid.isEmpty()) throw new InvalidFieldsException(invalid);
        return new Submission(senderRef, senderName, subject, body, groupRef, Boolean.TRUE.equals(request.retry()),
                copies, attachments);
    }

    /**
     * The files, decoded and checked against {@code mail.send.max-attachments} and
     * {@code mail.send.max-attachment-bytes}. Every refusal says the number it was measured
     * against, because the person who sees it is the one who chose the files.
     *
     * <p>The total is measured twice: once on the base64 as it arrived, which is a third larger
     * than the file and costs nothing to add up, and again on the decoded bytes. The first is what
     * stops the service from decoding a body that is obviously too big — the decoded copy would
     * otherwise sit in memory beside the encoded one for no reason — and the second is the real
     * check, since base64 can be padded or split.
     */
    private static List<Attachment> attachments(List<SubmitRequest.Attachment> given, MailProperties.Send send,
                                                Map<String, String> invalid) {
        if (given == null || given.isEmpty()) return List.of();
        long limit = send.getMaxAttachmentBytes();
        if (given.size() > send.getMaxAttachments()) {
            invalid.put("attachments", "At most " + send.getMaxAttachments() + " files can be attached to one email");
            return List.of();
        }
        long atLeast = given.stream().filter(a -> a != null && a.content() != null)
                .mapToLong(a -> atLeastDecoded(a.content().length())).sum();
        if (atLeast > limit) {
            invalid.put("attachments", tooLarge(atLeast, limit));
            return List.of();
        }

        List<Attachment> decoded = new ArrayList<>(given.size());
        long total = 0;
        for (int i = 0; i < given.size(); i++) {
            SubmitRequest.Attachment file = given.get(i) == null
                    ? new SubmitRequest.Attachment(null, null, null) : given.get(i);
            String field = "attachments[" + i + "].";
            String filename = fileName(clean(file.filename()));
            if (filename == null) invalid.put(field + "filename", "Enter the file name");
            String contentType = clean(file.contentType());
            if (contentType != null && contentType.length() > MailAttachment.CONTENT_TYPE_MAX) {
                invalid.put(field + "contentType", "The content type is too long");
                contentType = null;
            }
            byte[] content;
            try {
                // The lenient decoder: base64 in mail is often wrapped, and a line break is not an error.
                content = Base64.getMimeDecoder().decode(file.content() == null ? "" : file.content());
            } catch (IllegalArgumentException e) {
                invalid.put(field + "content", "The file is not base64");
                continue;
            }
            if (content.length == 0) {
                invalid.put(field + "content", "The file is empty");
                continue;
            }
            total += content.length;
            decoded.add(new Attachment(filename, contentType == null ? Attachment.DEFAULT_CONTENT_TYPE : contentType,
                    content));
        }
        if (total > limit) invalid.put("attachments", tooLarge(total, limit));
        // The caller throws when anything was put in `invalid`, so a half-decoded list never reaches a save.
        return List.copyOf(decoded);
    }

    /**
     * The fewest bytes this many base64 characters can decode to: four characters carry three
     * bytes, and the last group may be two of them padding. Deliberately the floor and not the
     * ceiling — the point is to refuse a body that cannot possibly fit before decoding doubles it
     * in memory, never to refuse one that would have fitted. (Base64 broken across lines decodes
     * to less than this, so a client that wraps its files very hard could be refused a few bytes
     * early; the backend sends one unbroken string.)
     */
    private static long atLeastDecoded(int encodedLength) {
        return Math.max(0, (long) encodedLength / 4 * 3 - 2);
    }

    /** Says how big it is, how big it may be, and why the ceiling is where it is. */
    static String tooLarge(long total, long limit) {
        return "The attachments are too large: " + megabytes(total) + " in all, and at most "
                + megabytes(limit) + " (" + limit + " bytes) can be sent — base64 makes files a third"
                + " larger as they go out, and Gmail refuses a message over "
                + megabytes(MailProperties.PROVIDER_MESSAGE_LIMIT_BYTES);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000d);
    }

    /**
     * The name as it will go into the message — {@link GmailMime#cleanFileName} decides that, so
     * what is stored is what is sent — cut to its column. Null when nothing usable is left.
     */
    private static String fileName(String given) {
        String name = MailText.fit(GmailMime.cleanFileName(given), MailAttachment.FILENAME_MAX);
        return name.isBlank() ? null : name;
    }

    /** Storable and trimmed; blank is null. */
    private static String clean(String value) {
        String text = MailText.storable(value);
        return text == null || text.isBlank() ? null : text.trim();
    }
}
