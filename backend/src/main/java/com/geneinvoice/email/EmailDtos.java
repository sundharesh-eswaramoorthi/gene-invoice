package com.geneinvoice.email;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.email.connection.GmailConnectionDtos.DeliveryGmailDto;
import com.geneinvoice.email.connection.GmailStatus;

import java.time.Instant;
import java.util.List;

public class EmailDtos {

    /**
     * A From or To entry as the compose form sends it: {@code {"type": "USER", "userId": 7}},
     * {@code {"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER"}} or
     * {@code {"type": "CUSTOMER"}}. A ROLE without a level is read as {@link EmailTargets#defaultLevel} (L7).
     */
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

    /** Checked in the service rather than by annotations: a bulk send carries the same fields in its params. */
    public record SendEmailRequest(
            String entityType,
            Long entityId,
            /** Optional: omitted means the caller. */
            EmailToken from,
            List<EmailToken> to,
            String subject,
            /** Optional. */
            String body,
            /**
             * Optional: documents already on this record or on its customer, attached without
             * uploading them again (E17). Ids of {@link com.geneinvoice.document.Document} rows,
             * as {@code GET /api/emails/attachable} offers them; anything else is refused by name.
             */
            List<Long> documentIds
    ) {}

    /**
     * How a person came to be on an email. {@code role}, {@code level} and {@code label} are set for
     * a ROLE source only, the label naming the level it came from: "Sales POC (this invoice)".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String type, String role, String level, String label) {}

    /**
     * A sender or recipient as the caller may see them. For a customer viewer, staff are
     * {@code masked}: the role they hold here, or the team, with no name, address or id (E13).
     * {@code delivery} is how an outbound email's To recipient got it; null for masked people, the
     * sender, received mail, and anyone of whom nothing is known (no copy, not read in the app).
     */
    public record Participant(String name, String address, Long userId, boolean internal, boolean masked,
                              List<Source> sources, RecipientDelivery delivery) {}

    /**
     * One recipient's copy, as the mail service reported it, and whether they read it in the app.
     * {@code status} is null when there was no copy: no address, or the email predates the mail service.
     */
    public record RecipientDelivery(
            RecipientDeliveryStatus status,
            /** For a customer viewer, only the app's own reasons; any other failure is "Could not be delivered". */
            String error,
            Instant sentAt,
            Instant deliveredAt,
            /** False when "delivered" only means no bounce came back in time. */
            boolean deliveredConfirmed,
            /** Read in the recipient's Gmail. */
            Instant readAt,
            Instant bouncedAt,
            /** Read in the app's Inbox. */
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
            /** Which POC the From role meant; null when From was a person. */
            RoleLevel fromRoleLevel,
            /** The From role with its level, e.g. "Sales POC (this invoice)". */
            String fromRoleLabel,
            List<Participant> to,
            List<Participant> cc,
            List<Unresolved> unresolved,
            /** Null for received mail; the team, without an id, for a customer viewer. */
            SentBy sentBy,
            /** For a customer viewer, null unless their own customer sent it. */
            String deliveredFrom,
            /** For a customer viewer, only the app's own reasons; any other failure is "Could not be delivered". */
            String error,
            int attempts,
            Instant occurredAt,
            Instant sentAt,
            boolean canRetry,
            /** The caller can open the record the email is about; a recipient may read an email whose record they cannot see. */
            boolean canOpenRecord,
            /** Null when the caller is not a To recipient. */
            Boolean readByMe,
            /** What the sender attached, in the order they chose it; empty for most email (E17). */
            List<AttachmentDto> attachments
    ) {}

    /**
     * A document that went out with an email, as it was when it was sent (E17). {@code documentId}
     * is the document it was taken from, so the tab can link to the Documents tab's own download,
     * which applies the document's visibility itself (D7); the file may have been deleted since,
     * which is why the name, type and size are the email's own snapshot.
     */
    public record AttachmentDto(Long id, Long documentId, String filename, String contentType,
                                long sizeBytes) {}

    /**
     * A document the compose form may offer: {@code id} is what goes into
     * {@link SendEmailRequest#documentIds()}, and {@code source} says whether it was found on the
     * record itself or on its customer (E17).
     */
    public record AttachableDto(Long id, String filename, String contentType, long sizeBytes,
                                AttachmentSource source, String sourceLabel) {}

    /**
     * {@code problems} would make Send fail; {@code warnings} do not stop it, but say the email will
     * be saved in the app and not sent. {@code subject} and {@code body} are the text with this
     * record's placeholders already filled in — exactly what would be stored and read (M3).
     */
    public record PreviewDto(Participant from, List<Participant> to, List<Unresolved> unresolved,
                             List<String> problems, List<String> warnings,
                             String subject, String body) {}

    /**
     * One placeholder the compose form may offer. {@code key} is written {@code {{Customer.Name}}},
     * and {@code sample} is what it fills in on <em>this</em> record, so the writer reads what it
     * will say before sending (M4). An empty sample means the record has nothing there.
     */
    public record PlaceholderDto(String key, String label, String sample) {}

    /** The placeholders of one level, e.g. "Customer" and then "Invoice" on an invoice (M1). */
    public record PlaceholderGroup(String label, List<PlaceholderDto> placeholders) {}

    // ---- compose context -------------------------------------------------------

    /** {@code gmail} says whether they can send from their own Gmail; always NOT_CONNECTED for a customer login. */
    public record PersonDto(Long userId, String name, String email, GmailStatus gmail) {}

    public record PeopleDto(Long userId, String name, String username, String email, GmailStatus gmail) {}

    public record Delivery(boolean configured) {}

    public record Sender(boolean restricted, PersonDto self) {}

    /**
     * A role at one level offered on the compose form, customer level first (L2, L3).
     * {@code levelLabel} is "Customer" or the record's noun, and {@code groupLabel} the heading the
     * form groups the chips under. {@code people} is everyone the role reaches in To, the primary
     * first; {@code sender} is the one who sends when it is the From (L5). {@code resolved} is null
     * without a record; {@code people} is empty and {@code sender} null when unresolved, without a
     * record, or masked.
     */
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

    // ---- inbox and delivery ------------------------------------------------------

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

    /** Whether email is sent at all, and the caller's own Gmail as the app last heard of it (null for customer logins). */
    public record DeliveryStatusDto(boolean configured, DeliveryGmailDto gmail) {}

    public record SyncResultDto(boolean enabled, int fetched, int imported, String error) {}
}
