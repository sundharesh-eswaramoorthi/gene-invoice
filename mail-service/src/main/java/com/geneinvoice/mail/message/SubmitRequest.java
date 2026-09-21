package com.geneinvoice.mail.message;

import java.util.List;

public record SubmitRequest(Sender sender, String subject, String body, String groupRef, Boolean retry,
                            List<Copy> copies) {

    public record Sender(String ownerRef, String name) {}

    public record Copy(String externalId, Recipient to) {}

    public record Recipient(String name, String address) {}

    public record Response(List<CopyState> copies) {}
}
