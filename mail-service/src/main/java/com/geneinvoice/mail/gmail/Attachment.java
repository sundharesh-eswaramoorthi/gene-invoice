package com.geneinvoice.mail.gmail;

/**
 * One file on an outgoing message: the bytes exactly as they will go out, with the name and the
 * type the recipient's mail program is told. The backend chose all three when the email was sent;
 * nothing here is read back off a document, so a file renamed afterwards still goes out as it was.
 *
 * <p>This is a carrier, not a value: {@code content} is a {@code byte[]}, so the record's
 * {@code equals} and {@code hashCode} compare the array by identity. Nothing compares attachments,
 * and the array is deliberately not copied — a copy would double the memory of every send for no
 * gain, as the only holders ({@link GmailMime} and the send worker) read it and never write it.
 */
public record Attachment(String filename, String contentType, byte[] content) {

    /** What a file with no usable type of its own is sent as; every mail program understands it. */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public Attachment {
        if (content == null) content = new byte[0];
    }

    public int sizeBytes() {
        return content.length;
    }
}
