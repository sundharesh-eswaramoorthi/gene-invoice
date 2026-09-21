package com.geneinvoice.email;

import com.geneinvoice.document.Document;
import com.geneinvoice.document.DocumentRepository;
import com.geneinvoice.document.DocumentStorage;
import com.geneinvoice.document.DocumentStorageException;
import com.geneinvoice.email.transport.AttachmentPart;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.MailSendException;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.NoopMailTransport;
import com.geneinvoice.email.transport.Submission;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hands saved outbound email to the mail service (mail-service.md §5.4), which queues and sends one
 * copy per To recipient and reports back on each. Every database step is its own short transaction,
 * and none is open while the service is called: a slow or hanging service must never hold a
 * connection.
 */
@Component
@Slf4j
public class EmailDispatcher {

    static final String NOT_CONFIGURED = NoopMailTransport.NOT_CONFIGURED;
    static final String NO_ADDRESS = "No recipient has an email address";
    static final String CUSTOMER_SENDER = "Email from a customer login is not sent through Gmail";
    static final String INTERRUPTED = "Sending was interrupted; retry to send again";
    static final String NOT_ACCEPTED = "The mail service did not accept this copy";
    /** An attached file could not be read back, so nothing is sent: an email missing its files is worse. */
    static final String ATTACHMENT_UNREADABLE = "An attached file could not be read: ";
    static final int MAX_ATTEMPTS = 3;

    /** How long a SENDING row may go untouched before the hand-off is taken to have died. */
    private static final Duration STALE_SENDING = Duration.ofMinutes(10);
    /** A newly saved email is left to the request that saved it for this long. */
    private static final Duration SETTLE = Duration.ofSeconds(30);
    private static final int SWEEP_BATCH = 200;

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final EmailAttachmentRepository attachmentRepository;
    private final DocumentRepository documentRepository;
    private final DocumentStorage documentStorage;
    private final MailTransport transport;
    private final TransactionTemplate transactions;
    private final boolean async;
    /** One thread, so a large bulk send reaches the service one email at a time. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "email-dispatch");
        t.setDaemon(true);
        return t;
    });
    /**
     * Emails handed to the background thread that it has not dispatched yet. The sweeper leaves them
     * to it, or a bulk send slower than the settle time would reach the service two at a time. The
     * queue does not survive a restart, and neither does this, so the sweeper then sends them.
     */
    private final Set<Long> backgroundOwned = ConcurrentHashMap.newKeySet();

    public EmailDispatcher(EmailRepository emailRepository, EmailRecipientRepository recipientRepository,
                           EmailAttachmentRepository attachmentRepository, DocumentRepository documentRepository,
                           DocumentStorage documentStorage, MailTransport transport, TransactionTemplate transactions,
                           @Value("${app.mail.dispatch.async:true}") boolean async) {
        this.emailRepository = emailRepository;
        this.recipientRepository = recipientRepository;
        this.attachmentRepository = attachmentRepository;
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
        this.transport = transport;
        this.transactions = transactions;
        this.async = async;
    }

    @PreDestroy
    void shutdown() {
        // Whatever is still queued stays QUEUED, and the sweeper sends it after a restart.
        background.shutdownNow();
    }

    /**
     * Hands one queued email to the mail service unless someone else already took it. It is then
     * the roll-up of its copies (usually QUEUED at the service), NOT_SENT, FAILED, or QUEUED here
     * for another hand-off.
     */
    public void dispatch(Long emailId) {
        dispatch(emailId, Instant.now());
    }

    /** @param now the time a retry's wait is measured against: the sweep's own clock when the sweeper sends */
    private void dispatch(Long emailId, Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Email must be dispatched outside a transaction");
        }
        Claimed claimed = transactions.execute(status -> claim(emailId, now));
        if (claimed == null) return;

        Submission submission;
        List<CopyState> states;
        try {
            // The bytes are read here, with no transaction open: storage may be a disk or an object
            // store, and neither should be waited on while a pooled connection is held. Nothing lazy
            // is touched — the rows the claim read are plain columns, copied into AttachmentRefs.
            submission = claimed.submission().withAttachments(read(claimed.attachments()));
            states = transport.submit(submission);
        } catch (MailSendException e) {
            transactions.executeWithoutResult(status -> failed(emailId, e.getMessage(), e.isTransientFailure()));
            return;
        } catch (RuntimeException e) {
            // A fault in the transport itself will not cure itself on a retry.
            log.warn("Email {} could not be handed to the mail transport", emailId, e);
            transactions.executeWithoutResult(status -> failed(emailId,
                    e.getMessage() == null ? "Delivery failed" : "Delivery failed: " + e.getMessage(), false));
            return;
        }
        transactions.executeWithoutResult(status -> handedOff(emailId, submission, states));
    }

    /** A bulk send's emails, one after another on the background thread — or right here when async dispatch is off. */
    public void dispatchAll(List<Long> emailIds) {
        if (emailIds.isEmpty()) return;
        List<Long> ids = List.copyOf(emailIds);
        if (!async) {
            ids.forEach(this::dispatchQuietly);
            return;
        }
        backgroundOwned.addAll(ids);
        try {
            background.execute(() -> ids.forEach(id -> {
                try {
                    dispatchQuietly(id);
                } finally {
                    backgroundOwned.remove(id);
                }
            }));
        } catch (RejectedExecutionException e) {
            ids.forEach(backgroundOwned::remove);
            log.warn("Shutting down; {} email(s) stay queued for the sweeper", ids.size());
        }
    }

    public void sweep() {
        sweep(Instant.now());
    }

    /**
     * Hands over what is due — retries whose wait is over, and anything a request or a restart left
     * queued — and gives up on hand-offs that died half way. Those are marked FAILED rather than
     * repeated on their own; a retry hands them over again. What the background thread still holds
     * is left to it. Emails the service has are its business, not the sweeper's.
     */
    void sweep(Instant now) {
        Instant touchedBefore = now.minus(STALE_SENDING);
        List<Long> stale = transactions.execute(status ->
                emailRepository.findStaleSending(touchedBefore, PageRequest.of(0, SWEEP_BATCH)));
        int interrupted = 0;
        for (Long id : stale == null ? List.<Long>of() : stale) {
            if (Boolean.TRUE.equals(transactions.execute(status -> interrupted(id, touchedBefore)))) interrupted++;
        }
        if (interrupted > 0) {
            log.warn("Marked {} interrupted email hand-off(s) as failed", interrupted);
        }
        // Enough rows that those the background thread holds cannot crowd out the rest.
        int held = backgroundOwned.size();
        List<Long> due = transactions.execute(status ->
                emailRepository.findDue(now, now.minus(SETTLE), PageRequest.of(0, SWEEP_BATCH + held)));
        if (due == null) return;
        due.stream()
                .filter(id -> !backgroundOwned.contains(id))
                .limit(SWEEP_BATCH)
                .forEach(id -> dispatchQuietly(id, now));
    }

    private void dispatchQuietly(Long emailId) {
        dispatchQuietly(emailId, Instant.now());
    }

    private void dispatchQuietly(Long emailId, Instant now) {
        try {
            dispatch(emailId, now);
        } catch (RuntimeException e) {
            log.warn("Dispatching email {} failed; the sweeper will pick it up", emailId, e);
        }
    }

    /**
     * A claimed email: the copies to hand over, and what has to be read out of document storage
     * before they can go. The two are kept apart because the first is built with a transaction
     * open and the second must be read with none.
     */
    private record Claimed(Submission submission, List<AttachmentRef> attachments) {}

    /**
     * One file to read: what the email recorded it was sending (E17), and the key storage holds it
     * under. A null {@code storageKey} means the document row itself has gone.
     */
    private record AttachmentRef(Long documentId, String filename, String contentType, String storageKey) {}

    /**
     * Claims the email and settles whatever needs no mail service (steps 1–3). Returns the copies
     * to hand over, or null when there is nothing to hand over.
     */
    private Claimed claim(Long emailId, Instant due) {
        if (emailRepository.claim(emailId, due, Instant.now()) == 0) return null;
        Email email = emailRepository.findById(emailId).orElseThrow();
        List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(emailId);
        if (recipients.stream().noneMatch(r -> r.getDeliveryStatus() != null)) {
            // Saved before the mail service: everyone it goes to gets a copy of their own now.
            recipients.stream().filter(r -> r.getField() == RecipientField.TO && r.getAddress() != null)
                    .forEach(r -> r.setDeliveryStatus(RecipientDeliveryStatus.QUEUED));
        }
        List<EmailRecipient> queued = recipients.stream()
                .filter(r -> r.getDeliveryStatus() == RecipientDeliveryStatus.QUEUED).toList();
        if (!transport.isConfigured()) {
            notSent(email, recipients, queued, NOT_CONFIGURED);
            return null;
        }
        if (recipients.stream().noneMatch(r -> r.getDeliveryStatus() != null)) {
            notSent(email, recipients, queued, NO_ADDRESS);
            return null;
        }
        // Customer logins do not connect Gmail, so there is nothing to send theirs from.
        if (!email.isFromInternal()) {
            notSent(email, recipients, queued, CUSTOMER_SENDER);
            return null;
        }
        if (queued.isEmpty()) {
            // Nothing left to hand over: the email is what its copies are.
            EmailDeliveryRollup.apply(email, recipients);
            email.setNextAttemptAt(null);
            emailRepository.save(email);
            return null;
        }
        recipientRepository.saveAll(recipients);
        // Only copies still queued here go: new ones, and failed or unsent ones the user retried. The
        // service never sends a copy it has twice, so asking it to retry is safe whichever this is (§7).
        Submission submission = new Submission(email.getFromUserId(), email.getFromName(), email.getSubject(),
                email.getBody(), String.valueOf(emailId), true,
                queued.stream().map(r -> new CopyRequest(CopyRef.externalId(emailId, r.getId()),
                        r.getName(), r.getAddress())).toList());
        return new Claimed(submission, attachmentRefs(emailId));
    }

    /**
     * What the email recorded it was attaching, with each document's storage key looked up now. The
     * name, the type and the order come from the attachment rows, which are the snapshot taken when
     * the email was sent — so a document renamed since still goes out under the name the sender saw.
     * A document deleted since still goes out too: deleting is soft and the bytes are retained, and
     * an email already queued was decided before the deletion.
     */
    private List<AttachmentRef> attachmentRefs(Long emailId) {
        List<EmailAttachment> attached = attachmentRepository.findByEmailIdOrderByIdAsc(emailId);
        if (attached.isEmpty()) return List.of();
        Map<Long, String> keys = documentRepository.findAllById(
                        attached.stream().map(EmailAttachment::getDocumentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Document::getId, Document::getStorageKey));
        return attached.stream()
                .map(a -> new AttachmentRef(a.getDocumentId(), a.getFilename(), a.getContentType(),
                        keys.get(a.getDocumentId())))
                .toList();
    }

    /**
     * The files themselves, read out of storage with no transaction open. A file that cannot be
     * read stops the whole email: sending it with some of its attachments, or none, would tell the
     * recipient the invoice was attached when it was not. The failure is final rather than retried
     * — a missing row and an unreadable file do not cure themselves — and a retry by hand will try
     * again once whoever owns the storage has put it right.
     */
    private List<AttachmentPart> read(List<AttachmentRef> refs) {
        if (refs.isEmpty()) return List.of();
        List<AttachmentPart> parts = new ArrayList<>(refs.size());
        for (AttachmentRef ref : refs) {
            if (ref.storageKey() == null) {
                throw new MailSendException(ATTACHMENT_UNREADABLE + ref.filename()
                        + " (document #" + ref.documentId() + " is no longer there)", false);
            }
            try (InputStream bytes = documentStorage.open(ref.storageKey())) {
                parts.add(new AttachmentPart(ref.filename(), ref.contentType(), bytes.readAllBytes()));
            } catch (IOException | DocumentStorageException e) {
                throw new MailSendException(ATTACHMENT_UNREADABLE + ref.filename()
                        + " (" + e.getMessage() + ")", false, e);
            }
        }
        return parts;
    }

    /** Every queued copy is not sent, for the same reason; an email with no copies is not sent either. */
    private void notSent(Email email, List<EmailRecipient> recipients, List<EmailRecipient> queued, String reason) {
        queued.forEach(r -> {
            r.setDeliveryStatus(RecipientDeliveryStatus.NOT_SENT);
            r.setDeliveryError(reason);
        });
        recipientRepository.saveAll(recipients);
        if (!EmailDeliveryRollup.apply(email, recipients)) {
            email.setStatus(EmailStatus.NOT_SENT);
            email.setError(reason);
        }
        email.setNextAttemptAt(null);
        emailRepository.save(email);
    }

    /**
     * The service accepted the copies: record where each stands and roll them up, with the email
     * locked, as its reports may be arriving at the same time.
     */
    private void handedOff(Long emailId, Submission submission, List<CopyState> states) {
        Email email = emailRepository.findByIdForUpdate(emailId).orElse(null);
        if (email == null) return;
        List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(emailId);
        Map<Long, EmailRecipient> byId = recipients.stream()
                .collect(Collectors.toMap(EmailRecipient::getId, Function.identity()));
        Set<String> reported = states.stream().map(CopyState::externalId).collect(Collectors.toSet());
        for (CopyState state : states) {
            CopyRef.parse(state.externalId())
                    .filter(ref -> ref.emailId() == emailId)
                    .map(ref -> byId.get(ref.recipientId()))
                    .ifPresent(copy -> EmailDeliveryRollup.applyCopy(email, copy, state));
        }
        for (CopyRequest asked : submission.copies()) {
            if (reported.contains(asked.externalId())) continue;
            // Not the service's contract, but a copy left queued here would never be handed over again.
            log.warn("The mail service did not report copy {}", asked.externalId());
            CopyRef.parse(asked.externalId()).map(ref -> byId.get(ref.recipientId()))
                    .filter(copy -> copy.getDeliveryStatus() == RecipientDeliveryStatus.QUEUED)
                    .ifPresent(copy -> {
                        copy.setDeliveryStatus(RecipientDeliveryStatus.FAILED);
                        copy.setDeliveryError(NOT_ACCEPTED);
                    });
        }
        EmailDeliveryRollup.handedOff(email, Instant.now());
        EmailDeliveryRollup.apply(email, recipients);
        recipientRepository.saveAll(recipients);
        emailRepository.save(email);
    }

    /**
     * A hand-off nobody has touched for {@link #STALE_SENDING} died half way (a restart, say). The
     * service may or may not have the copies it was handing over; they fail, and a retry hands them
     * over again, which never sends one twice. The email is then what its copies are — a retried
     * PARTIAL email stays PARTIAL, as the copies that went out before still did — and FAILED only
     * when it has none. Checked again with the email locked: its hand-off may have finished since.
     */
    private boolean interrupted(Long emailId, Instant touchedBefore) {
        Email email = emailRepository.findByIdForUpdate(emailId).orElse(null);
        if (email == null || email.getStatus() != EmailStatus.SENDING || email.getHandedOffAt() != null
                || !email.getUpdatedAt().isBefore(touchedBefore)) {
            return false;
        }
        List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(emailId);
        recipients.stream().filter(r -> r.getDeliveryStatus() == RecipientDeliveryStatus.QUEUED).forEach(r -> {
            r.setDeliveryStatus(RecipientDeliveryStatus.FAILED);
            r.setDeliveryError(INTERRUPTED);
        });
        recipientRepository.saveAll(recipients);
        if (!EmailDeliveryRollup.apply(email, recipients)) {
            email.setStatus(EmailStatus.FAILED);
            email.setError(INTERRUPTED);
        }
        email.setNextAttemptAt(null);
        emailRepository.save(email);
        return true;
    }

    /**
     * A transient failure is handed over again after 1 minute, then 5; the third failure, or any
     * permanent one, is final, and the copies waiting on it fail with it. A report that the service
     * has the copies after all (a lost answer) wins over the failure.
     */
    private void failed(Long emailId, String message, boolean transientFailure) {
        Email email = emailRepository.findByIdForUpdate(emailId).orElse(null);
        // The sweeper may have given up on it while the service was still answering.
        if (email == null || email.getStatus() != EmailStatus.SENDING || email.getHandedOffAt() != null) return;
        String error = EmailText.fit(message == null ? "Delivery failed" : message, Email.ERROR_MAX);
        if (transientFailure && email.getAttempts() < MAX_ATTEMPTS) {
            email.setStatus(EmailStatus.QUEUED);
            email.setError(error);
            email.setNextAttemptAt(Instant.now().plus(
                    email.getAttempts() <= 1 ? Duration.ofMinutes(1) : Duration.ofMinutes(5)));
            emailRepository.save(email);
            return;
        }
        List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(emailId);
        recipients.stream().filter(r -> r.getDeliveryStatus() == RecipientDeliveryStatus.QUEUED).forEach(r -> {
            r.setDeliveryStatus(RecipientDeliveryStatus.FAILED);
            r.setDeliveryError(error);
        });
        recipientRepository.saveAll(recipients);
        // Copies a retry left alone keep what they had: some may have gone out before.
        if (!EmailDeliveryRollup.apply(email, recipients)) {
            email.setStatus(EmailStatus.FAILED);
            email.setError(error);
        }
        email.setNextAttemptAt(null);
        emailRepository.save(email);
    }
}
