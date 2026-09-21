package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.Submission;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class MailServiceDtos {

    public record Party(String name, String address) {}

    public record Sender(String ownerRef, String name) {}

    public record Copy(String externalId, Party to) {}

    public record SubmitRequest(Sender sender, String subject, String body, String groupRef, boolean retry,
                                List<Copy> copies) {

        static SubmitRequest of(Submission s) {
            return new SubmitRequest(new Sender(String.valueOf(s.senderUserId()), s.senderName()),
                    s.subject(), s.body(), s.groupRef(), s.retry(),
                    s.copies().stream().map(c -> new Copy(c.externalId(), new Party(c.name(), c.address()))).toList());
        }
    }

    public record SubmitResponse(List<CopyState> copies) {}

    public record ConnectRequest(String ownerName, String clientId, String clientSecret, String refreshToken) {
        @Override
        public String toString() {
            return "ConnectRequest[ownerName=" + ownerName + ", clientId=" + clientId + ", secrets hidden]";
        }
    }

    public record ErrorBody(Integer status, String error, String message, Map<String, String> fieldErrors) {}

    public record EventBatch(List<Event> events) {}

    public record Event(long id, String type, Instant occurredAt, JsonNode data) {}

    public record MessageReceived(String ownerRef, String mailboxAddress, String repliedToExternalId,
                                  String providerMessageId, String providerThreadId, String rfcMessageId,
                                  String inReplyTo, List<String> references, Party from, List<Party> to,
                                  List<Party> cc, String subject, String body, Instant receivedAt) {}
}
