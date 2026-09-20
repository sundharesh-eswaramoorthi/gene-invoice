package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.Submission;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The mail service's API and webhook, as JSON (mail-service.md §4.3, §4.8). */
public class MailServiceDtos {

    // ---- calls to the service -------------------------------------------------------

    public record Party(String name, String address) {}

    public record Sender(String ownerRef, String name) {}

    public record Copy(String externalId, Party to) {}

    /** {@code POST /api/v1/messages}. */
    public record SubmitRequest(Sender sender, String subject, String body, String groupRef, boolean retry,
                                List<Copy> copies) {

        static SubmitRequest of(Submission s) {
            return new SubmitRequest(new Sender(String.valueOf(s.senderUserId()), s.senderName()),
                    s.subject(), s.body(), s.groupRef(), s.retry(),
                    s.copies().stream().map(c -> new Copy(c.externalId(), new Party(c.name(), c.address()))).toList());
        }
    }

    public record SubmitResponse(List<CopyState> copies) {}

    /** {@code PUT /api/v1/connections/{ownerRef}}. Carries secrets: never logged, and its toString says so. */
    public record ConnectRequest(String ownerName, String clientId, String clientSecret, String refreshToken) {
        @Override
        public String toString() {
            return "ConnectRequest[ownerName=" + ownerName + ", clientId=" + clientId + ", secrets hidden]";
        }
    }

    /** How the service words a refusal. */
    public record ErrorBody(Integer status, String error, String message, Map<String, String> fieldErrors) {}

    // ---- the webhook ----------------------------------------------------------------

    /** {@code POST /api/mail-service/events}: events in the order they happened. */
    public record EventBatch(List<Event> events) {}

    /** {@code data} is read by {@code type}: a copy's state, a received reply, or a connection's state. */
    public record Event(long id, String type, Instant occurredAt, JsonNode data) {}

    /** The {@code data} of {@code message.received}: a reply found in {@code ownerRef}'s mailbox. */
    public record MessageReceived(String ownerRef, String mailboxAddress, String repliedToExternalId,
                                  String providerMessageId, String providerThreadId, String rfcMessageId,
                                  String inReplyTo, List<String> references, Party from, List<Party> to,
                                  List<Party> cc, String subject, String body, Instant receivedAt) {}
}
