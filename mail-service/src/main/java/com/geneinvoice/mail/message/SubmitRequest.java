package com.geneinvoice.mail.message;

import java.util.List;

/**
 * The body of {@code POST /api/v1/messages}: one email from one sender, as a copy per recipient.
 *
 * @param retry send again copies that are {@code FAILED} or {@code NOT_SENT}; others are never sent again
 */
public record SubmitRequest(Sender sender, String subject, String body, String groupRef, Boolean retry,
                            List<Copy> copies) {

    /** The backend's user who sends it, and their name for the From header. */
    public record Sender(String ownerRef, String name) {}

    public record Copy(String externalId, Recipient to) {}

    public record Recipient(String name, String address) {}

    /** The answer: every copy as it now stands, in request order. */
    public record Response(List<CopyState> copies) {}
}
