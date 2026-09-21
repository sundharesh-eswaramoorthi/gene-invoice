package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.document.Document;
import com.geneinvoice.document.DocumentEntityType;
import com.geneinvoice.document.DocumentRepository;
import com.geneinvoice.document.DocumentVisibility;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Attaching files that are already in the app to an email about a record (E17): the documents on
 * the record itself, and the documents on that record's customer. Nothing is uploaded again and no
 * bytes move through the compose form — the sender picks from what is there, and the email keeps a
 * snapshot of what they picked.
 *
 * <p>What may be picked is settled here and nowhere else, so the endpoint that lists the offer and
 * the send that takes ids straight from a client agree exactly. The checks run in the order the
 * documents feature itself uses (AC-C11): the document privilege, then the record — which the
 * email service has already loaded under the caller's customer restriction and POC book — then, for
 * a customer login, the document's own visibility (D7).
 *
 * <p>What is attached is both stored against the email — so the Email tab shows what went with it —
 * and carried out to the recipients: the dispatcher reads each document's bytes as it submits, and
 * the mail service writes them as a {@code multipart/mixed} message, one part per file (E18).
 */
@Component
@RequiredArgsConstructor
public class EmailAttachments {

    /**
     * How many documents one email may carry. A cap belongs here rather than on the column: the
     * list is written into one email's history and into one MIME message, and a compose form that
     * let someone attach a customer's whole filing cabinet would make both unreadable.
     */
    public static final int MAX_ATTACHMENTS = 10;

    /**
     * How large those documents may be in total, counted as the files' own bytes before any
     * encoding — the same figure, to the byte, as the mail service's
     * {@code mail.send.max-attachment-bytes} (its {@code MailProperties.Send}). The number is
     * Gmail's: base64 makes a file a third larger on its way out and Gmail refuses a message over
     * 25 MB, so 17 MiB of files is about 24.4 MB sent, with the headers and the body still to fit.
     *
     * <p>The two ends have to agree, and this end has to be the one that says no first (E18). A
     * document is capped at {@code app.documents.max-size-bytes} — 10 MiB — one at a time, so ten
     * of them come to a hundred, and until this cap existed the backend took that email, saved it,
     * and only then heard the mail service refuse it: a 400 that {@code MailServiceClient} reads as
     * permanent, so the send failed for good with the compose form long gone and nothing on the
     * Email tab a sender could do about it. Refusing here means the person who chose the files is
     * told, while they are still choosing them, which files are too many.
     */
    public static final long MAX_ATTACHMENT_BYTES = 17L * 1024 * 1024;

    private final DocumentRepository documentRepository;
    private final EmailAttachmentRepository attachmentRepository;
    private final CurrentUser currentUser;

    // ---- what may be attached ------------------------------------------------------------

    /**
     * The documents offered for a record: its own first, then its customer's, each in the order
     * they were uploaded. A caller without {@code DOCUMENT_VIEW} is offered nothing rather than
     * refused — they may still write the email, they simply have no files to choose from — and
     * asking for one by id anyway is refused by {@link #resolve}.
     */
    @Transactional(readOnly = true)
    public List<EmailDtos.AttachableDto> offered(EmailTargets.Target target) {
        if (!currentUser.has(Privileges.DOCUMENT_VIEW)) return List.of();
        boolean restricted = currentUser.isCustomer();
        DocumentEntityType own = documentType(target.type());
        return onRecordOrCustomer(target).stream()
                .filter(d -> !restricted || d.getVisibility() == DocumentVisibility.SHARED)
                .map(d -> {
                    AttachmentSource source = sourceOf(d, target, own);
                    return new EmailDtos.AttachableDto(d.getId(), d.getFilename(), d.getContentType(),
                            d.getSizeBytes(), source, source.label(target.type()));
                })
                // The record's own files are what a sender reaches for first; the customer's back them up.
                .sorted(Comparator.comparing(a -> a.source() == AttachmentSource.RECORD ? 0 : 1))
                .toList();
    }

    /**
     * The chosen documents in the order they were chosen, refusing — without naming it — anything
     * that is not this record's to send. Empty ids attach nothing, which almost every email does.
     */
    @Transactional(readOnly = true)
    public List<Document> resolve(EmailTargets.Target target, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        if (!currentUser.has(Privileges.DOCUMENT_VIEW)) throw new AccessDeniedException("Not allowed");
        return chosen(target, documentIds, currentUser.isCustomer());
    }

    /**
     * The same, for a send with nobody logged in — an automation rule firing on a background
     * thread (R1). There is no caller to hold a privilege or to be restricted to a customer, so
     * only the rule that was written by someone who was allowed to write it stands between the
     * record and the file; the scope check that the document is on this record or its customer is
     * the same one a request gets.
     */
    @Transactional(readOnly = true)
    public List<Document> resolveInBackground(EmailTargets.Target target, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        return chosen(target, documentIds, false);
    }

    private List<Document> chosen(EmailTargets.Target target, List<Long> documentIds, boolean restricted) {
        // The same document asked for twice is one attachment, not two copies of one file.
        List<Long> wanted = documentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.size() > MAX_ATTACHMENTS) {
            throw new BadRequestException("At most " + MAX_ATTACHMENTS + " documents can be attached to one email");
        }
        Map<Long, Document> found = documentRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        DocumentEntityType own = documentType(target.type());
        List<Document> chosen = new ArrayList<>();
        for (Long id : wanted) {
            Document document = found.get(id);
            // Nobody must learn from a refusal that a document they may not attach exists, so one
            // that is not there, one that is another record's, and one a customer login may not
            // see are refused in the same words — and none of them names a file (D7, AC-C11).
            // Naming it here told a customer login the filename of any shared document of any
            // other customer, by asking to attach its id.
            if (document == null
                    || (restricted && document.getVisibility() != DocumentVisibility.SHARED)
                    || sourceOf(document, target, own) == null) {
                throw new BadRequestException("Document #" + id + " is not on " + target.label() + " or its customer");
            }
            // Past here it is a file this record could have attached and the sender knows of, so it
            // is named: they are told what became of it rather than that it was never there.
            if (document.isDeleted()) {
                throw new BadRequestException(name(document) + " has been deleted");
            }
            chosen.add(document);
        }
        refuseIfTooLarge(chosen);
        return chosen;
    }

    /**
     * Refuses a set of files the mail service could not carry. The total is a fact about the choice
     * and not about any one file — each of them may be well inside every other limit — so it is
     * measured once the whole choice is known and reported in whole: the message says what the
     * files come to and what may be sent, which is what a sender needs to decide which one to take
     * off. Worded and rounded exactly as the mail service words its own refusal, so the two numbers
     * read the same whichever end says no.
     */
    private static void refuseIfTooLarge(List<Document> chosen) {
        long total = chosen.stream().mapToLong(Document::getSizeBytes).sum();
        if (total > MAX_ATTACHMENT_BYTES) {
            throw new BadRequestException("The attachments are too large: " + megabytes(total)
                    + " in all, and at most " + megabytes(MAX_ATTACHMENT_BYTES) + " ("
                    + MAX_ATTACHMENT_BYTES + " bytes) can be sent with one email");
        }
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000d);
    }

    /**
     * Every live document of the record's customer, which is every document that could be on the
     * record or on its customer: a document's {@code customerId} is its record's customer whatever
     * kind of record it hangs off, so one read answers both halves of the offer. Those on the
     * customer's <em>other</em> invoices and payments are dropped here — they belong to another
     * record's conversation. A record with no customer (a product, a role) has nothing to offer.
     */
    private List<Document> onRecordOrCustomer(EmailTargets.Target target) {
        if (target.customerId() == null) return List.of();
        DocumentEntityType own = documentType(target.type());
        return documentRepository.findByCustomerIdAndDeletedFalseOrderByIdAsc(target.customerId()).stream()
                .filter(d -> sourceOf(d, target, own) != null)
                .toList();
    }

    /**
     * Which half of the offer a document is in, or null when it is neither. A customer's own
     * documents are read as the customer's even when the email is about the customer, so an email
     * about a customer says "Customer" rather than "This customer".
     */
    private static AttachmentSource sourceOf(Document document, EmailTargets.Target target,
                                             DocumentEntityType own) {
        if (document.getEntityType() == DocumentEntityType.CUSTOMER
                && document.getEntityId().equals(target.customerId())) {
            return AttachmentSource.CUSTOMER;
        }
        if (own != null && document.getEntityType() == own && document.getEntityId().equals(target.id())) {
            return AttachmentSource.RECORD;
        }
        return null;
    }

    /**
     * The kind of record documents hang off that matches this kind of email target, or null when
     * there is none: promises, disputes, products, users and roles carry no documents of their own,
     * and so are offered their customer's files alone (§4.5).
     */
    private static DocumentEntityType documentType(EmailEntityType type) {
        return switch (type) {
            case CUSTOMER -> DocumentEntityType.CUSTOMER;
            case INVOICE -> DocumentEntityType.INVOICE;
            case PAYMENT -> DocumentEntityType.PAYMENT;
            default -> null;
        };
    }

    private static String name(Document document) {
        return "Document #" + document.getId() + " (" + document.getFilename() + ")";
    }

    // ---- what went out --------------------------------------------------------------------

    /**
     * Writes the chosen documents against the saved email, as they are at this moment: the name,
     * the type and the size are copied rather than read back later, so the Email tab still says
     * what was sent after the document is renamed or deleted. Runs in the transaction that saved
     * the email.
     */
    List<EmailAttachment> save(Long emailId, List<Document> documents) {
        if (documents.isEmpty()) return List.of();
        return attachmentRepository.saveAll(documents.stream().map(d -> EmailAttachment.builder()
                .emailId(emailId)
                .documentId(d.getId())
                .filename(d.getFilename())
                .contentType(d.getContentType())
                .sizeBytes(d.getSizeBytes())
                .build()).toList());
    }

    /** What one email carried. */
    public List<EmailDtos.AttachmentDto> of(Long emailId) {
        return attachmentRepository.findByEmailIdOrderByIdAsc(emailId).stream()
                .map(EmailAttachments::toDto).toList();
    }

    /** What a page of emails carried, in one read; an email with nothing attached is absent. */
    public Map<Long, List<EmailDtos.AttachmentDto>> byEmail(Collection<Long> emailIds) {
        if (emailIds.isEmpty()) return Map.of();
        return attachmentRepository.findByEmailIdInOrderByIdAsc(emailIds).stream()
                .collect(Collectors.groupingBy(EmailAttachment::getEmailId,
                        Collectors.mapping(EmailAttachments::toDto, Collectors.toList())));
    }

    private static EmailDtos.AttachmentDto toDto(EmailAttachment a) {
        return new EmailDtos.AttachmentDto(a.getId(), a.getDocumentId(), a.getFilename(),
                a.getContentType(), a.getSizeBytes());
    }
}
