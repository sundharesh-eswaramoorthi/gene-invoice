package com.geneinvoice.email;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class EmailDtos {

    /** What an email is about. */
    public enum TargetType { CUSTOMER, INVOICE }

    // ---- requests ----------------------------------------------------------------

    /** The sender: a user (type USER) or a role (type ROLE), by id. */
    public record Sender(
            @NotNull(message = "is required") EmailPartyType type,
            @NotNull(message = "is required") Long id
    ) {}

    /**
     * The To line. Any mix of users, roles and the customer's own addresses, with at least one.
     * {@code customerEmails} names particular addresses of the customer; {@code allCustomerEmails}
     * takes every address the customer has — the only choice in a bulk send, where each row has its
     * own customer.
     */
    public record Recipients(
            List<Long> userIds,
            List<Long> roleIds,
            List<String> customerEmails,
            Boolean allCustomerEmails
    ) {
        public List<Long> users() { return userIds == null ? List.of() : userIds; }
        public List<Long> roles() { return roleIds == null ? List.of() : roleIds; }
        public List<String> addresses() { return customerEmails == null ? List.of() : customerEmails; }
        public boolean allAddresses() { return Boolean.TRUE.equals(allCustomerEmails); }
    }

    /** The form itself, filled in once whether it sends one email or one per selected row. */
    public record Compose(
            @NotNull(message = "is required") @Valid Sender from,
            @NotNull(message = "is required") Recipients to,
            @NotBlank(message = "is required") @Size(max = FieldLimits.EMAIL_SUBJECT) String subject,
            @Size(max = FieldLimits.EMAIL_BODY) String body
    ) {}

    /** One email about a customer or an invoice: exactly one of the two ids. */
    public record SendRequest(
            Long customerId,
            Long invoiceId,
            @NotNull(message = "is required") @Valid Sender from,
            @NotNull(message = "is required") Recipients to,
            @NotBlank(message = "is required") @Size(max = FieldLimits.EMAIL_SUBJECT) String subject,
            @Size(max = FieldLimits.EMAIL_BODY) String body
    ) {
        public Compose compose() {
            return new Compose(from, to, subject, body);
        }
    }

    /**
     * One email per selected customer or invoice. The selection works like every other bulk action:
     * explicit ids, or everything matching the list's filter, re-resolved through the caller's scope.
     */
    public record BulkSendRequest(
            @NotNull(message = "is required") TargetType targetType,
            List<Long> ids,
            Boolean selectAllMatchingFilter,
            String sort,
            List<String> filters,
            @NotNull(message = "is required") @Valid Compose email
    ) {}

    // ---- responses ---------------------------------------------------------------

    public record SenderDto(EmailPartyType type, Long userId, Long roleId, String name, String address) {}

    public record MemberDto(Long userId, String name, String address) {}

    public record RecipientDto(EmailPartyType type, Long userId, Long roleId, String name, String address,
                               /** Who was in the role at send time; empty for anything but a role. */
                               List<MemberDto> members) {}

    public record EmailDto(
            Long id,
            TargetType targetType,
            Long customerId,
            String customerName,
            /** Set, with its number, when the email is about an invoice. */
            Long invoiceId,
            String invoiceNumber,
            SenderDto from,
            List<RecipientDto> to,
            String subject,
            String body,
            Long sentByUserId,
            String sentByName,
            Instant sentAt
    ) {}

    /** An email as it sits in one user's Inbox, with that user's own read status. */
    public record InboxItemDto(Long id, boolean read, Instant readAt, EmailDto email) {}

    /** Someone the compose form can offer as a sender or a recipient. */
    public record StaffDto(Long id, String username, String fullName, String email, String role) {
        static StaffDto from(User u) {
            return new StaffDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(),
                    u.getRole() == null ? null : u.getRole().getName());
        }
    }

    /** A role the compose form can offer, with its mailbox and how many people it would reach now. */
    public record RoleOptionDto(Long id, String name, String email, long memberCount) {}

    /** Every address on the customer an email about this customer or invoice can go to. */
    public record AddressesDto(Long customerId, String customerName, Long invoiceId, String invoiceNumber,
                               List<String> addresses) {}
}
