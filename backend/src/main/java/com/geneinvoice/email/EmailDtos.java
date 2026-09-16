package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wire shapes for the stored-Email API. */
public final class EmailDtos {

    private EmailDtos() {}

    /** A From choice: {"kind":"USER"|"ROLE", id, label, email-at-send-time-if-known}. */
    public record SenderOption(String kind, Long id, String label, String email) {}

    /** A To choice: users and roles; the Customer address is a flag on the request instead. */
    public record RecipientOption(String kind, Long id, String label, String email) {}

    public record Options(List<SenderOption> senders, List<RecipientOption> recipients) {}

    /** What the compose dialog submits. Exactly one of fromUserId / fromRoleId. */
    public record SendRequest(Long fromUserId, Long fromRoleId,
                              List<Long> toUserIds, List<Long> toRoleIds,
                              boolean includeCustomerAddress,
                              String subject, String body) {

        /** The same draft arriving as bulk-action params. */
        public static SendRequest fromParams(Map<String, Object> p) {
            Map<String, Object> params = p == null ? Map.of() : p;
            return new SendRequest(
                    asLong(params.get("fromUserId")), asLong(params.get("fromRoleId")),
                    asLongList(params.get("toUserIds")), asLongList(params.get("toRoleIds")),
                    asBoolean(params.get("includeCustomerAddress")),
                    asString(params.get("subject")), asString(params.get("body")));
        }

        private static Long asLong(Object v) {
            if (v == null) return null;
            if (v instanceof Number n) return n.longValue();
            try {
                return Long.valueOf(v.toString().trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("Expected a whole number but got: " + v);
            }
        }

        private static List<Long> asLongList(Object v) {
            if (v == null) return List.of();
            if (!(v instanceof List<?> list)) {
                throw new BadRequestException("Expected a list of ids but got: " + v);
            }
            List<Long> out = new ArrayList<>();
            for (Object item : list) out.add(asLong(item));
            return out;
        }

        private static boolean asBoolean(Object v) {
            return Boolean.TRUE.equals(v) || "true".equals(v);
        }

        private static String asString(Object v) {
            return v == null ? null : v.toString();
        }
    }

    /** A single send's outcome: either the Email that now exists, or why none was created. */
    public record SendOutcome(boolean created, Long emailId, String message) {}

    /** One row of a Customer or Invoice Email tab. */
    public record RecordEmailSummary(Long id, String subject, String senderDisplay,
                                     Instant sentAt, String link, String invoiceNumber,
                                     int recipientCount) {}

    public record RecipientDto(String kind, String label, String address, String roleName) {
        static RecipientDto from(EmailRecipient r) {
            return new RecipientDto(r.getKind().name(), r.getLabel(), r.getAddress(), r.getRoleName());
        }
    }

    public record Detail(Long id, String subject, String body, String senderDisplay,
                         String senderAddress, Instant sentAt, String sentByDisplay,
                         Long customerId, Long invoiceId, String invoiceNumber,
                         List<RecipientDto> recipients, Boolean read) {}

    /** One row of the caller's Inbox: their read row plus the Email's envelope facts. */
    public record InboxEntry(Long id, Long emailId, String subject, String senderDisplay,
                             Instant sentAt, boolean read) {}
}
