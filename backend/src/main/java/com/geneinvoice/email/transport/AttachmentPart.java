package com.geneinvoice.email.transport;

/**
 * One file going out with an email, read out of document storage as the email is handed over
 * (E17, E18). The copies of an email all carry the same files, so this hangs off the
 * {@link Submission} rather than off a {@link CopyRequest}.
 *
 * @param filename    what the recipient's mail program shows, as it was when the email was sent
 * @param contentType the media type it is sent as, e.g. {@code application/pdf}
 * @param content     the file itself; base64 on the wire, bytes here
 */
public record AttachmentPart(String filename, String contentType, byte[] content) {

    /**
     * A carrier, not a value: two parts are the same only when they hold the same array. Nothing
     * compares them, and the array is not copied — a copy would double the memory of every send.
     */
    public AttachmentPart {
        if (content == null) content = new byte[0];
    }

    public int sizeBytes() {
        return content.length;
    }
}
