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
    /** What a retry sends again. A bounce is the recipient's server saying no, which another try will not change. */
    private static final Set<RecipientDeliveryStatus> RETRIED = EnumSet.of(FAILED, NOT_SENT);
    private static final String UNEXPLAINED = "Delivery failed";

    /**
     * Records a copy's state as the mail service reported it, unless a later report was applied
     * already: the service counts every change in {@code seq}, and a copy only moves forward.
     * Returns whether anything changed.
     */
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

    /**
     * Sets the email's status, error and sent time from its copies. An email with no copies (nobody
     * had an address, or it was sent before the mail service) is left as it is; returns false then.
     */
    public static boolean apply(Email email, List<EmailRecipient> recipients) {
        List<EmailRecipient> copies = recipients.stream().filter(r -> r.getDeliveryStatus() != null).toList();
        if (copies.isEmpty()) return false;
        email.setStatus(status(copies));
        email.setError(error(copies));
        email.setSentAt(copies.stream().map(EmailRecipient::getSentAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null));
        return true;
    }

    /** Anything still in progress wins, sending first; then all out, all unsent, all failed, or a mix. */
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

    /** Null when nothing failed; the reason when every failure gives the same one; else a count and the first. */
    static String error(List<EmailRecipient> copies) {
        List<EmailRecipient> failed = copies.stream().filter(c -> DID_NOT.contains(c.getDeliveryStatus())).toList();
        if (failed.isEmpty()) return null;
        Set<String> reasons = new LinkedHashSet<>();
        failed.forEach(c -> reasons.add(c.getDeliveryError() == null ? UNEXPLAINED : c.getDeliveryError()));
        String first = reasons.iterator().next();
        if (reasons.size() == 1) return first;
        return EmailText.fit(failed.size() + " of " + copies.size() + " not delivered: " + first, Email.ERROR_MAX);
    }

    /**
     * A retry sends the copies that failed or were not sent — or, for an email sent before the
     * mail service, which has no copies, the whole email again. Bounced copies are not retried.
     */
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

    /**
     * Puts what {@link #canRetry} allows back in the queue, not yet handed off, with a fresh count
     * of attempts. The copies keep their {@code seq}: the service's count goes on from there.
     */
    static void requeue(Email email, List<EmailRecipient> recipients) {
        boolean hasCopies = recipients.stream().anyMatch(r -> r.getDeliveryStatus() != null);
        for (EmailRecipient r : recipients) {
            boolean again = hasCopies
                    ? r.getDeliveryStatus() != null && RETRIED.contains(r.getDeliveryStatus())
                    // An older email: everyone it would have gone to now gets a copy of their own.
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

    /**
     * The service has the copies: from now on it sends them, and neither the dispatcher nor its
     * sweeper hands them over again. The hand-off's answer or the service's first report of them
     * counts, whichever came first.
     */
    public static void handedOff(Email email, Instant now) {
        if (email.getHandedOffAt() == null) email.setHandedOffAt(now);
        email.setNextAttemptAt(null);
    }
}
