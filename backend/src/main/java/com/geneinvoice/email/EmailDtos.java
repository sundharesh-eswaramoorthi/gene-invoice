package com.geneinvoice.email;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.email.connection.GmailConnectionDtos.DeliveryGmailDto;
import com.geneinvoice.email.connection.GmailStatus;

import java.time.Instant;
import java.util.List;

public class EmailDtos {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmailToken(String type, Long userId, String role, String level) {
        public static EmailToken user(Long userId) {
            return new EmailToken("USER", userId, null, null);
        }

        public static EmailToken role(RoleRef role) {
            return new EmailToken("ROLE", null, role.role().name(), role.level().name());
        }

        public static EmailToken customer() {
            return new EmailToken("CUSTOMER", null, null, null);
        }
    }

    public record SendEmailRequest(
            String entityType,
            Long entityId,
            EmailToken from,
            List<EmailToken> to,
            String subject,
            String body
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String type, String role, String level, String label) {}

    public record Participant(String name, String address, Long userId, boolean internal, boolean masked,
                              List<Source> sources, RecipientDelivery delivery) {}

    public record RecipientDelivery(
            RecipientDeliveryStatus status,
            String error,
            Instant sentAt,
            Instant deliveredAt,
            boolean deliveredConfirmed,
            Instant readAt,
            Instant bouncedAt,
            Instant readInAppAt
    ) {}

    public record Unresolved(String token, String label, String reason) {}

    public record SentBy(Long userId, String name) {}

    public record EmailDto(
            Long id,
            EmailEntityType entityType,
            Long entityId,
            String entityLabel,
            String entityLink,
            EmailDirection direction,
            EmailStatus status,
            String subject,
            String body,
            Participant from,
            EmailRole fromRole,
            RoleLevel fromRoleLevel,
            String fromRoleLabel,
            List<Participant> to,
            List<Participant> cc,
            List<Unresolved> unresolved,
            SentBy sentBy,
            String deliveredFrom,
            String error,
            int attempts,
            Instant occurredAt,
            Instant sentAt,
            boolean canRetry,
            boolean canOpenRecord,
            Boolean readByMe
    ) {}

    public record PreviewDto(Participant from, List<Participant> to, List<Unresolved> unresolved,
                             List<String> problems, List<String> warnings) {}

    public record PersonDto(Long userId, String name, String email, GmailStatus gmail) {}

    public record PeopleDto(Long userId, String name, String username, String email, GmailStatus gmail) {}

    public record Delivery(boolean configured) {}

    public record Sender(boolean restricted, PersonDto self) {}

    public record RoleOption(EmailRole role, String label, RoleLevel level, String levelLabel,
                             String groupLabel, Boolean resolved, List<PersonDto> people,
                             PersonDto sender) {}

    public record CustomerAddress(String name, String address) {}

    public record CustomerEmails(boolean available, List<CustomerAddress> addresses) {}

    public record Suggestion(String subject, String body, List<EmailToken> to) {}

    public record ContextDto(
            EmailEntityType entityType,
            Long entityId,
            String entityLabel,
            String entityLink,
            Delivery delivery,
            Sender sender,
            List<RoleOption> roles,
            CustomerEmails customerEmails,
            Suggestion suggestion
    ) {}

    public record InboxItemDto(
            Long id,
            Long emailId,
            EmailEntityType entityType,
            Long entityId,
            String entityLabel,
            String entityLink,
            String subject,
            String snippet,
            Participant from,
            EmailDirection direction,
            EmailStatus status,
            Instant occurredAt,
            boolean read
    ) {}

    public record DeliveryStatusDto(boolean configured, DeliveryGmailDto gmail) {}

    public record SyncResultDto(boolean enabled, int fetched, int imported, String error) {}
}
