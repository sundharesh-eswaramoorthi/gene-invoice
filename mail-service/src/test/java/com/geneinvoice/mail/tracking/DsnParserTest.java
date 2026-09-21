package com.geneinvoice.mail.tracking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DsnParserTest {

    private static final String ORIGINAL = "<gm-5b0c7c1e-0000-4000-8000-000000000001@gmail.com>";

    private static Optional<DsnParser.Report> parse(String mime) throws Exception {
        return DsnParser.parse(mime.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void gmailsFailureNoticeNamesTheRecipientTheReasonAndTheOriginal() throws Exception {
        DsnParser.Report report = parse(Bounces.gmailFailure("bob@acme.com", ORIGINAL)).orElseThrow();

        assertThat(report.failed()).isTrue();
        assertThat(report.delayed()).isFalse();
        assertThat(report.originalMessageId()).isEqualTo(ORIGINAL);
        assertThat(report.recipients()).singleElement().satisfies(r -> {
            assertThat(r.address()).isEqualTo("bob@acme.com");
            assertThat(r.action()).isEqualTo("failed");
            assertThat(r.status()).isEqualTo("5.1.1");
        });
        assertThat(report.failedRecipients()).containsExactly("bob@acme.com");
        assertThat(report.failedAddresses()).containsExactly("bob@acme.com");
        assertThat(report.error("BOB@acme.com")).isEqualTo(Bounces.GMAIL_FAILURE_ERROR);
        assertThat(report.error("ravi@acme.com")).isEqualTo(Bounces.GMAIL_FAILURE_ERROR);
    }

    @Test
    void aDelayIsNotAFailure() throws Exception {
        DsnParser.Report report = parse(Bounces.gmailDelay("bob@acme.com", ORIGINAL)).orElseThrow();

        assertThat(report.failed()).isFalse();
        assertThat(report.delayed()).isTrue();
        assertThat(report.originalMessageId()).isEqualTo(ORIGINAL);
        assertThat(report.failedAddresses()).isEmpty();
    }

    @Test
    void xFailedRecipientsFromTheMailerDaemonIsAFailureOnItsOwn() throws Exception {
        DsnParser.Report report = parse(Bounces.eximFailure("bob@acme.com")).orElseThrow();

        assertThat(report.failed()).isTrue();
        assertThat(report.recipients()).isEmpty();
        assertThat(report.failedAddresses()).containsExactly("bob@acme.com");
        assertThat(report.originalMessageId()).isNull();
        assertThat(report.error("bob@acme.com")).isEqualTo("The recipient's mail server rejected the message");
    }

    @Test
    void theOriginalRecipientAndTheHeadersPartAreEnough() throws Exception {
        DsnParser.Report report = parse(Bounces.headersOnlyFailure("Bob@Acme.com", ORIGINAL)).orElseThrow();

        assertThat(report.failed()).isTrue();
        assertThat(report.originalMessageId()).isEqualTo(ORIGINAL);
        assertThat(report.failedAddresses()).containsExactly("bob@acme.com");
        assertThat(report.error("bob@acme.com")).isEqualTo("5.2.2 X-Postfix; mailbox full");
    }

    @Test
    void aReplyIsNoReportEvenFromAnAutomaticSender() throws Exception {
        assertThat(parse(Bounces.reply("bob@acme.com", ORIGINAL))).isEmpty();
        assertThat(parse(Bounces.crlf("From: MAILER-DAEMON@mx.acme.com", "Subject: Hello", "", "Hi", ""))).isEmpty();
        assertThat(parse(Bounces.crlf("From: bob@acme.com", "X-Failed-Recipients: ravi@acme.com", "Subject: Fwd", "",
                "Hi", ""))).isEmpty();
        assertThat(parse(Bounces.crlf("From: bob@acme.com",
                "Content-Type: multipart/report; report-type=disposition-notification; boundary=\"m\"", "",
                "--m", "Content-Type: text/plain", "", "Read.", "--m--", ""))).isEmpty();
    }

    @Test
    void aReportThatSaysNothingFailedIsNeitherAFailureNorADelay() throws Exception {
        DsnParser.Report report = parse(Bounces.crlf(
                "From: postmaster@mx.acme.com",
                "Content-Type: multipart/report; report-type=delivery-status; boundary=\"d\"",
                "",
                "--d",
                "Content-Type: message/delivery-status",
                "",
                "Reporting-MTA: dns; mx.acme.com",
                "",
                "Final-Recipient: rfc822; bob@acme.com",
                "Action: delivered",
                "Status: 2.0.0",
                "",
                "--d--",
                "")).orElseThrow();

        assertThat(report.failed()).isFalse();
        assertThat(report.delayed()).isFalse();
    }

    @Test
    void aVeryLongDiagnosticIsCut() throws Exception {
        String longText = "x".repeat(1200);
        DsnParser.Report report = parse(Bounces.crlf(
                "From: postmaster@mx.acme.com",
                "Content-Type: multipart/report; report-type=delivery-status; boundary=\"d\"",
                "",
                "--d",
                "Content-Type: message/delivery-status",
                "",
                "Final-Recipient: rfc822; bob@acme.com",
                "Action: failed",
                "Status: 5.0.0",
                "Diagnostic-Code: smtp; " + longText,
                "",
                "--d--",
                "")).orElseThrow();

        assertThat(report.error("bob@acme.com")).hasSize(1000).startsWith("5.0.0 xxx").endsWith("…");
    }
}
