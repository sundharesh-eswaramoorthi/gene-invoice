package com.geneinvoice.mail.message;

import java.util.List;

/**
 * The body of {@code POST /api/v1/messages}: one email from one sender, as a copy per recipient.
 *
 * @param retry       send again copies that are {@code FAILED} or {@code NOT_SENT}; others are never sent again
 * @param attachments the files every copy of this email carries, in the order they should appear;
 *                    absent or empty for the great majority of emails, which carry none
 */
public record SubmitRequest(Sender sender, String subject, String body, String groupRef, Boolean retry,
                            List<Copy> copies, List<Attachment> attachments) {

    /** The backend's user who sends it, and their name for the From header. */
    public record Sender(String ownerRef, String name) {}

    public record Copy(String externalId, Recipient to) {}

    public record Recipient(String name, String address) {}

    /**
     * One file, whole, in the submission. JSON cannot carry bytes, so {@code content} is the file
     * base64-encoded (standard alphabet, padding optional) — which is also how it leaves again on
     * its way to Gmail, so nothing is encoded twice for the sake of the wire.
     *
     * @param filename    what the recipient sees; no directory part, and never empty
     * @param contentType the media type the recipient's mail program is told, e.g.
     *                    {@code application/pdf}; blank falls back to {@code application/octet-stream}
     */
    public record Attachment(String filename, String contentType, String content) {

        /** The bytes stay out of the log: a file's contents are the customer's, not ours to print. */
        @Override
        public String toString() {
            return "Attachment[filename=" + filename + ", contentType=" + contentType
                    + ", content=" + (content == null ? 0 : content.length()) + " base64 chars]";
        }
    }

    /** The answer: every copy as it now stands, in request order. */
    public record Response(List<CopyState> copies) {}
}
