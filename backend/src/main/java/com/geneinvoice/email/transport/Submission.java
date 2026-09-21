package com.geneinvoice.email.transport;

import java.util.List;

/**
 * One email's copies for the mail service to send, one per To recipient (M6).
 *
 * @param senderUserId the sender person's user id; their own connected Gmail sends it (M4)
 * @param senderName   the name in the From header
 * @param subject      a single line
 * @param body         plain text, possibly empty
 * @param groupRef     the email's id, so the service can tell which copies belong together
 * @param retry        a copy the service already failed or did not send is queued again
 * @param copies       at least one
 * @param attachments  the files every copy carries (E17), read out of document storage at hand-off;
 *                     empty for the great majority of emails, which carry none
 */
public record Submission(long senderUserId, String senderName, String subject, String body, String groupRef,
                         boolean retry, List<CopyRequest> copies, List<AttachmentPart> attachments) {

    /** An email with nothing attached, which is most of them. */
    public Submission(long senderUserId, String senderName, String subject, String body, String groupRef,
                      boolean retry, List<CopyRequest> copies) {
        this(senderUserId, senderName, subject, body, groupRef, retry, copies, List.of());
    }

    /**
     * The same submission with its files, which the dispatcher reads after the transaction that
     * built it has closed: a document's bytes must never be fetched with a database connection
     * held open.
     */
    public Submission withAttachments(List<AttachmentPart> attachments) {
        return new Submission(senderUserId, senderName, subject, body, groupRef, retry, copies, attachments);
    }
}
