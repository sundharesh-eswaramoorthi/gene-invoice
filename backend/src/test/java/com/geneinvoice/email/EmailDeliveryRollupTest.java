package com.geneinvoice.email;

import com.geneinvoice.email.transport.CopyState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.geneinvoice.email.RecipientDeliveryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** An email's status is the roll-up of its copies (M12), and a copy only moves forward. */
class EmailDeliveryRollupTest {

    private static EmailRecipient copy(RecipientDeliveryStatus status, String error) {
        return EmailRecipient.builder().field(RecipientField.TO).name("x").sources("USER")
                .deliveryStatus(status).deliveryError(error).build();
    }

    private static List<EmailRecipient> copies(RecipientDeliveryStatus... statuses) {
        List<EmailRecipient> copies = new ArrayList<>();
        for (RecipientDeliveryStatus s : statuses) copies.add(copy(s, s == FAILED || s == BOUNCED || s == NOT_SENT ? s.name() : null));
        return copies;
    }

    private static Email outbound(EmailStatus status) {
        return Email.builder().direction(EmailDirection.OUTBOUND).status(status).build();
    }

    @Test
    void theStatusTable() {
        assertThat(EmailDeliveryRollup.status(copies(SENDING, QUEUED, FAILED))).isEqualTo(EmailStatus.SENDING);
        assertThat(EmailDeliveryRollup.status(copies(QUEUED, READ, BOUNCED))).isEqualTo(EmailStatus.QUEUED);
        assertThat(EmailDeliveryRollup.status(copies(SENT, DELIVERED, READ))).isEqualTo(EmailStatus.SENT);
        assertThat(EmailDeliveryRollup.status(copies(NOT_SENT, NOT_SENT))).isEqualTo(EmailStatus.NOT_SENT);
        assertThat(EmailDeliveryRollup.status(copies(FAILED, BOUNCED, NOT_SENT))).isEqualTo(EmailStatus.FAILED);
        assertThat(EmailDeliveryRollup.status(copies(FAILED))).isEqualTo(EmailStatus.FAILED);
        assertThat(EmailDeliveryRollup.status(copies(DELIVERED, BOUNCED))).isEqualTo(EmailStatus.PARTIAL);
        assertThat(EmailDeliveryRollup.status(copies(READ, NOT_SENT))).isEqualTo(EmailStatus.PARTIAL);
    }

    @Test
    void theErrorIsTheOneReasonOrACountAndTheFirst() {
        assertThat(EmailDeliveryRollup.error(copies(SENT, QUEUED))).isNull();
        assertThat(EmailDeliveryRollup.error(List.of(copy(SENT, null), copy(FAILED, "Refused"), copy(NOT_SENT, "Refused"))))
                .isEqualTo("Refused");
        assertThat(EmailDeliveryRollup.error(List.of(copy(SENT, null), copy(BOUNCED, "550 No such user"),
                copy(FAILED, "Refused")))).isEqualTo("2 of 3 not delivered: 550 No such user");
        assertThat(EmailDeliveryRollup.error(List.of(copy(FAILED, null)))).isEqualTo("Delivery failed");
        String longReason = "r".repeat(Email.ERROR_MAX);
        assertThat(EmailDeliveryRollup.error(List.of(copy(FAILED, longReason), copy(FAILED, "Other"))))
                .hasSize(Email.ERROR_MAX).endsWith("…").startsWith("2 of 2 not delivered: rrr");
    }

    @Test
    void applyingSetsStatusErrorAndTheEarliestSendAndLeavesAnEmailWithoutCopiesAlone() {
        Email email = outbound(EmailStatus.SENDING);
        EmailRecipient late = copy(DELIVERED, null);
        late.setSentAt(Instant.parse("2026-09-20T10:05:00Z"));
        EmailRecipient early = copy(SENT, null);
        early.setSentAt(Instant.parse("2026-09-20T10:01:00Z"));
        EmailRecipient noAddress = EmailRecipient.builder().field(RecipientField.TO).name("y").sources("USER").build();

        assertThat(EmailDeliveryRollup.apply(email, List.of(late, noAddress, early))).isTrue();
        assertThat(email.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(email.getError()).isNull();
        assertThat(email.getSentAt()).isEqualTo(Instant.parse("2026-09-20T10:01:00Z"));

        Email older = outbound(EmailStatus.FAILED);
        older.setError("Gmail is unavailable (503)");
        assertThat(EmailDeliveryRollup.apply(older, List.of(noAddress))).isFalse();
        assertThat(older.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(older.getError()).isEqualTo("Gmail is unavailable (503)");
    }

    @Test
    void aCopyTakesOnlyALaterReport() {
        Email email = outbound(EmailStatus.QUEUED);
        EmailRecipient copy = copy(QUEUED, null);
        Instant sent = Instant.parse("2026-09-20T10:01:00Z");
        CopyState delivered = new CopyState("gi-1-2", "1", 4, DELIVERED, null, 1, "jane@gmail.com", sent, sent.plusSeconds(900),
                false, null, null, "18c2", "18c2t", "<gm-1@gmail.com>");

        assertThat(EmailDeliveryRollup.applyCopy(email, copy, delivered)).isTrue();
        assertThat(copy.getDeliveryStatus()).isEqualTo(DELIVERED);
        assertThat(copy.getDeliverySeq()).isEqualTo(4);
        assertThat(copy.getSentAt()).isEqualTo(sent);
        assertThat(copy.getDeliveredAt()).isEqualTo(sent.plusSeconds(900));
        assertThat(copy.getProviderThreadId()).isEqualTo("18c2t");
        assertThat(copy.getRfcMessageId()).isEqualTo("<gm-1@gmail.com>");
        assertThat(email.getDeliveredFrom()).isEqualTo("jane@gmail.com");

        CopyState older = new CopyState("gi-1-2", "1", 3, SENT, null, 1, "jane@gmail.com", sent, null,
                false, null, null, "18c2", "18c2t", "<gm-1@gmail.com>");
        assertThat(EmailDeliveryRollup.applyCopy(email, copy, older)).isFalse();
        assertThat(EmailDeliveryRollup.applyCopy(email, copy, delivered)).isFalse();
        assertThat(copy.getDeliveryStatus()).isEqualTo(DELIVERED);
    }

    @Test
    void whatCanBeRetried() {
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.PARTIAL), copies(SENT, FAILED))).isTrue();
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.NOT_SENT), copies(NOT_SENT))).isTrue();
        // A bounce is the recipient's server saying no; another try will not change that.
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.PARTIAL), copies(SENT, BOUNCED))).isFalse();
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.FAILED), copies(BOUNCED))).isFalse();
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.QUEUED), copies(QUEUED, FAILED))).isFalse();
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.SENT), copies(SENT))).isFalse();
        // Sent before the mail service: no copies, so the email as a whole.
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.FAILED), List.of())).isTrue();
        assertThat(EmailDeliveryRollup.canRetry(outbound(EmailStatus.SENT), List.of())).isFalse();
        Email received = Email.builder().direction(EmailDirection.INBOUND).status(EmailStatus.FAILED).build();
        assertThat(EmailDeliveryRollup.canRetry(received, List.of())).isFalse();
    }

    @Test
    void copyRefsAreTheAppsKeysAndNothingElse() {
        assertThat(CopyRef.externalId(91, 501)).isEqualTo("gi-91-501");
        assertThat(CopyRef.parse("gi-91-501")).contains(new CopyRef(91, 501));
        assertThat(CopyRef.parse("gi-91")).isEmpty();
        assertThat(CopyRef.parse("xx-91-501")).isEmpty();
        assertThat(CopyRef.parse("gi-91-501-2")).isEmpty();
        assertThat(CopyRef.parse(null)).isEmpty();
    }
}
