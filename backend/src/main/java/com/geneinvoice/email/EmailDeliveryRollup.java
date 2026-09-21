package com.geneinvoice.email;

import com.geneinvoice.email.transport.CopyState;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.geneinvoice.email.RecipientDeliveryStatus.*;

/**
 * An outbound email's delivery as the sum of its recipients' copies (M12): the email in the app
 * stays one email, and its status says how its copies are doing together.
 */
public final class EmailDeliveryRollup {

    private EmailDeliveryRollup() {}

    private static final Set<RecipientDeliveryStatus> WENT_OUT = EnumSet.of(SENT, DELIVERED, READ);
    private static final Set<RecipientDeliveryStatus> DID_NOT = EnumSet.of(FAILED, BOUNCED, NOT_SENT);
    private static final Set<RecipientDeliveryStatus> RETRIED = EnumSet.of(FAILED, NOT_SENT);
    private static final String UNEXPLAINED = "Delivery failed";

    public static boolean applyCopy(Email email, EmailRecipient copy, CopyState state) {
        if (state.status() == null || state.seq() <= copy.getDeliverySeq()) return false;
        copy.setDeliveryStatus(state.status());
        copy.setDeliveryError(EmailText.fit(EmailText.storable(state.error()), Email.ERROR_MAX));
        copy.setDeliverySeq(state.seq());
        copy.setSentAt(state.sentAt());
        copy.setDeliveredAt(state.deliveredAt());
        copy.setDeliveredConfirmed(state.deliveredConfirmed());
        copy.setMailReadAt(state.readAt());
        copy.setBouncedAt(state.bouncedAt());
        copy.setProviderMessageId(state.providerMessageId());
        copy.setProviderThreadId(state.providerThreadId());
        copy.setRfcMessageId(state.rfcMessageId());
        if (email.getDeliveredFrom() == null && state.fromAddress() != null) {
            email.setDeliveredFrom(EmailText.fit(state.fromAddress(), Email.ADDRESS_MAX));
        }
        return true;
    }

    public static boolean apply(Email email, List<EmailRecipient> recipients) {
        List<EmailRecipient> copies = recipients.stream().filter(r -> r.getDeliveryStatus() != null).toList();
        if (copies.isEmpty()) return false;
        email.setStatus(status(copies));
        email.setError(error(copies));
        email.setSentAt(copies.stream().map(EmailRecipient::getSentAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null));
        return true;
    }

    static EmailStatus status(List<EmailRecipient> copies) {
        Set<RecipientDeliveryStatus> seen = EnumSet.noneOf(RecipientDeliveryStatus.class);
        copies.forEach(c -> seen.add(c.getDeliveryStatus()));
        if (seen.contains(SENDING)) return EmailStatus.SENDING;
        if (seen.contains(QUEUED)) return EmailStatus.QUEUED;
        if (WENT_OUT.containsAll(seen)) return EmailStatus.SENT;
        if (seen.equals(EnumSet.of(NOT_SENT))) return EmailStatus.NOT_SENT;
        if (DID_NOT.containsAll(seen)) return EmailStatus.FAILED;
        return EmailStatus.PARTIAL;
    }

    static String error(List<EmailRecipient> copies) {
        List<EmailRecipient> failed = copies.stream().filter(c -> DID_NOT.contains(c.getDeliveryStatus())).toList();
        if (failed.isEmpty()) return null;
        Set<String> reasons = new LinkedHashSet<>();
        failed.forEach(c -> reasons.add(c.getDeliveryError() == null ? UNEXPLAINED : c.getDeliveryError()));
        String first = reasons.iterator().next();
        if (reasons.size() == 1) return first;
        return EmailText.fit(failed.size() + " of " + copies.size() + " not delivered: " + first, Email.ERROR_MAX);
    }

    public static boolean canRetry(Email email, List<EmailRecipient> recipients) {
        if (email.getDirection() != EmailDirection.OUTBOUND) return false;
        EmailStatus status = email.getStatus();
        if (status != EmailStatus.FAILED && status != EmailStatus.NOT_SENT && status != EmailStatus.PARTIAL) {
            return false;
        }
        List<EmailRecipient> copies = recipients.stream().filter(r -> r.getDeliveryStatus() != null).toList();
        if (copies.isEmpty()) return status != EmailStatus.PARTIAL;
        return copies.stream().anyMatch(c -> RETRIED.contains(c.getDeliveryStatus()));
    }

    static void requeue(Email email, List<EmailRecipient> recipients) {
        boolean hasCopies = recipients.stream().anyMatch(r -> r.getDeliveryStatus() != null);
        for (EmailRecipient r : recipients) {
            boolean again = hasCopies
                    ? r.getDeliveryStatus() != null && RETRIED.contains(r.getDeliveryStatus())
                    : r.getField() == RecipientField.TO && r.getAddress() != null;
            if (!again) continue;
            r.setDeliveryStatus(QUEUED);
            r.setDeliveryError(null);
        }
        email.setStatus(EmailStatus.QUEUED);
        email.setHandedOffAt(null);
        email.setAttempts(0);
        email.setError(null);
        email.setNextAttemptAt(null);
    }

    public static void handedOff(Email email, Instant now) {
        if (email.getHandedOffAt() == null) email.setHandedOffAt(now);
        email.setNextAttemptAt(null);
    }
}
