package com.geneinvoice.mail.gmail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Writing one copy, and reading mail the way other mail programs write it. */
class GmailMimeTest {

    static String crlf(String... lines) {
        return String.join("\r\n", lines);
    }

    private static IncomingMail parse(String mime) throws Exception {
        return GmailMime.parse("m-1", "t-1", null, mime.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void whatTheAppWritesItReadsBack() throws Exception {
        byte[] mime = GmailMime.build(new MailAddress("Zoë O'Brien, Accounts", "billing@company.com"),
                new MailAddress(null, "ap@acme.test"), "Статус счёта INV-0042", "Line one\nLine two",
                "<gi-1@company.com>", Instant.parse("2026-09-20T10:00:00Z"));

        IncomingMail read = GmailMime.parse("m-1", "t-1", Instant.EPOCH, mime);

        assertThat(read.from()).isEqualTo(new MailAddress("Zoë O'Brien, Accounts", "billing@company.com"));
        assertThat(read.to()).containsExactly(new MailAddress(null, "ap@acme.test"));
        assertThat(read.subject()).isEqualTo("Статус счёта INV-0042");
        assertThat(read.body()).isEqualTo("Line one\nLine two");
        assertThat(read.rfcMessageId()).isEqualTo("<gi-1@company.com>");
        assertThat(read.receivedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void aCopyIsWrittenForItsOneRecipientWithTheDateAndMessageIdGiven() throws Exception {
        MimeMessage copy = message(GmailMime.build(new MailAddress("Jane Doe", "jane@gmail.com"),
                new MailAddress("Acme Ltd", "ap@acme.test"), "Invoice", "Line one\nLine two\r\nLine three",
                "<gm-1@gmail.com>", Instant.parse("2026-09-20T10:00:00Z")));

        assertThat(copy.getRecipients(Message.RecipientType.TO)).extracting(a -> ((InternetAddress) a).getAddress())
                .containsExactly("ap@acme.test");
        assertThat(copy.getHeader("To", ",")).isEqualTo("Acme Ltd <ap@acme.test>");
        assertThat(copy.getHeader("Bcc")).isNull();
        assertThat(copy.getHeader("Cc")).isNull();
        assertThat(((InternetAddress) copy.getFrom()[0]).getAddress()).isEqualTo("jane@gmail.com");
        assertThat(copy.getMessageID()).isEqualTo("<gm-1@gmail.com>");
        assertThat(copy.getSentDate().toInstant()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
        assertThat(copy.getHeader("MIME-Version", null)).isEqualTo("1.0");
        assertThat(copy.getContentType()).isEqualToIgnoringCase("text/plain; charset=UTF-8");
        assertThat(copy.getContent()).isEqualTo("Line one\r\nLine two\r\nLine three");
    }

    @Test
    void anAddressJakartaMailRejectsCannotBeWritten() {
        assertThatThrownBy(() -> GmailMime.build(new MailAddress("Jane", "jane@gmail.com"),
                new MailAddress("Broken", "not an address"), "Hi", "", "<gm-1@gmail.com>", Instant.EPOCH))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("not an address");
    }

    private static MimeMessage message(byte[] mime) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
    }

    @Test
    void headersWrittenLooselyStillLink() throws Exception {
        IncomingMail read = parse(crlf(
                "From: ap@acme.test",
                "To: Billing <billing@company.com>,",
                "\tsomeone@elsewhere.test",
                "Subject: Re: statement",
                "Date: Wed, 16 Sep 2026 10:00:00 +0530",
                "Message-ID: bare-id@acme.test",
                "In-Reply-To: <gi-2@company.com> (Billing's message)",
                "References: gi-1@company.com gi-2@company.com",
                "",
                "No Content-Type at all.",
                ""));

        assertThat(read.from()).isEqualTo(new MailAddress(null, "ap@acme.test"));
        assertThat(read.to()).containsExactly(new MailAddress("Billing", "billing@company.com"),
                new MailAddress(null, "someone@elsewhere.test"));
        assertThat(read.rfcMessageId()).isEqualTo("bare-id@acme.test");
        assertThat(read.inReplyTo()).isEqualTo("<gi-2@company.com>");
        assertThat(read.references()).containsExactly("gi-1@company.com", "gi-2@company.com");
        assertThat(read.body()).isEqualTo("No Content-Type at all.");
        // Without Gmail's time, the Date header.
        assertThat(read.receivedAt()).isEqualTo(Instant.parse("2026-09-16T04:30:00Z"));
    }

    @Test
    void headersWrittenInRawUtf8AreReadAsUtf8() throws Exception {
        IncomingMail read = GmailMime.parse("m-1", "t-1", null, crlf(
                "From: Jürgen Müller <juergen@acme.test>",
                "To: \"Zoë O'Brien\" <zoe@acme.test>, billing@company.com",
                "Subject: Re: Invoice INV-0042 for ₹1,200.00",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                "Paid.",
                "").getBytes(StandardCharsets.UTF_8));

        assertThat(read.subject()).isEqualTo("Re: Invoice INV-0042 for ₹1,200.00");
        assertThat(read.from()).isEqualTo(new MailAddress("Jürgen Müller", "juergen@acme.test"));
        assertThat(read.to()).containsExactly(new MailAddress("Zoë O'Brien", "zoe@acme.test"),
                new MailAddress(null, "billing@company.com"));

        // What the app writes stays 7-bit: encoded words, which every receiver reads.
        byte[] written = GmailMime.build(new MailAddress("Jürgen Müller", "billing@company.com"),
                new MailAddress("Zoë", "zoe@acme.test"), "₹1,200.00 received", "Danke", "<gi-1@company.com>", Instant.EPOCH);
        assertThat(new String(written, StandardCharsets.ISO_8859_1).chars().allMatch(c -> c < 0x80)).isTrue();
    }

    @Test
    void nothingReadCarriesANul() throws Exception {
        IncomingMail read = parse(crlf(
                "From: =?UTF-8?Q?Acme=00_Accounts?= <ap@acme.test>",
                "Cc: =?UTF-8?Q?Ravi=00?= <ravi@acme.test>",
                "Subject: =?UTF-8?Q?Re=3A_Invoice=00_INV-0042?=",
                "Message-ID: <nul-1@acme.test>",
                "Content-Type: text/plain; charset=UTF-8",
                "Content-Transfer-Encoding: base64",
                "",
                // UTF-16 labelled as UTF-8: a NUL after every letter.
                Base64.getEncoder().encodeToString("Paid".getBytes(StandardCharsets.UTF_16LE)),
                ""));

        assertThat(read.subject()).isEqualTo("Re: Invoice INV-0042");
        assertThat(read.from()).isEqualTo(new MailAddress("Acme Accounts", "ap@acme.test"));
        assertThat(read.cc()).containsExactly(new MailAddress("Ravi", "ravi@acme.test"));
        assertThat(read.body()).isEqualTo("Paid");

        // As HTML reads them, neither NUL nor half of a UTF-16 pair is a character.
        assertThat(GmailMime.htmlToText("<p>a&#0;b&#x0000;c&#xD800;d&#57343;e</p>"))
                .isEqualTo("a\uFFFDb\uFFFDc\uFFFDd\uFFFDe");
    }

    @Test
    void htmlOfAnyShapeIsReducedInTimeInStepWithItsLength() {
        // About 1 MB each. The regular expressions this replaced read on to the end from every '<' (or
        // space) and took minutes on these; a request to sync waited that long too.
        List<String> shapes = List.of("<".repeat(1_000_000), "<script>".repeat(125_000), "<!--".repeat(250_000),
                "<li".repeat(333_334), "<div class=".repeat(90_000), "&nbsp;".repeat(170_000) + "x");
        for (String html : shapes) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> GmailMime.htmlToText(html),
                    () -> "Too slow on " + html.substring(0, 20) + "…");
        }
    }

    @Test
    void onlyTheStartOfLongHtmlIsRead() {
        String text = GmailMime.htmlToText("<p>Start</p>" + "<p>filler</p>".repeat(20_000) + "<p>End</p>");
        assertThat(text).startsWith("Start\n\nfiller").doesNotContain("End");

        // A tag the cut leaves half written is not taken for text.
        String prefix = "x".repeat(GmailMime.HTML_MAX - 10);
        assertThat(GmailMime.htmlToText(prefix + "<div class=\"note\">More</div>")).isEqualTo(prefix);

        // Nor half of a character outside the BMP: an emoji is two UTF-16 units, and this one straddles the cut.
        String upToTheEmoji = "x".repeat(GmailMime.HTML_MAX - 1);
        assertThat(GmailMime.htmlToText(upToTheEmoji + "\uD83D\uDE00" + "y")).isEqualTo(upToTheEmoji);

        // Nor half an entity, which would otherwise be read as the text "&nb".
        String upToTheEntity = "<p>Payment sent</p>" + "x".repeat(GmailMime.HTML_MAX - 19 - 3);
        assertThat(GmailMime.htmlToText(upToTheEntity + "&nbsp;y")).isEqualTo("Payment sent\n\n" + "x".repeat(GmailMime.HTML_MAX - 22));
        String upToTheNumber = "x".repeat(GmailMime.HTML_MAX - 4);
        assertThat(GmailMime.htmlToText(upToTheNumber + "&#8377;")).isEqualTo(upToTheNumber);
        // A plain ampersand well before the cut stays.
        String ampersand = "Smith & Sons " + "x".repeat(GmailMime.HTML_MAX);
        assertThat(GmailMime.htmlToText(ampersand)).startsWith("Smith & Sons x");
    }

    @Test
    void anUnknownCharsetIsReadAsUtf8AndAMissingSenderIsNull() throws Exception {
        String mime = crlf(
                "To: billing@company.com",
                "Subject: Charset",
                "Content-Type: text/plain; charset=x-unknown-charset",
                "Content-Transfer-Encoding: 8bit",
                "",
                "₹500 paid",
                "");

        IncomingMail read = GmailMime.parse("m-1", "t-1", null, mime.getBytes(StandardCharsets.UTF_8));

        assertThat(read.from()).isNull();
        assertThat(read.cc()).isEmpty();
        assertThat(read.references()).isEmpty();
        assertThat(read.body()).isEqualTo("₹500 paid");
        assertThat(read.receivedAt()).isNotNull();
    }

    @Test
    void htmlWrittenAsDivsPerLineKeepsItsLines() {
        // How Gmail's own editor writes a short message.
        assertThat(GmailMime.htmlToText("<div dir=\"ltr\">Thanks,<div>we will pay on Friday.</div><div><br></div><div>Ravi</div></div>"))
                .isEqualTo("Thanks,\nwe will pay on Friday.\n\nRavi");
    }

    @Test
    void htmlIsReducedToItsWords() {
        assertThat(GmailMime.htmlToText("""
                <!DOCTYPE html><html><head><title>Ignored</title><script>var x = "<p>no</p>";</script></head>
                <body><!-- a comment <p>hidden</p> -->
                  <div>Dear   team,</div>
                  <table><tr><td>Invoice</td><td>INV-0042</td></tr><tr><td>Amount</td><td>&#x20B9;1,200.00&nbsp;</td></tr></table>
                  <p>Regards,<br/>Acme &lt;Accounts&gt; &unknown;</p>
                </body></html>
                """))
                .isEqualTo("Dear team,\nInvoice INV-0042\nAmount ₹1,200.00\n\nRegards,\nAcme <Accounts> &unknown;");
    }
}
