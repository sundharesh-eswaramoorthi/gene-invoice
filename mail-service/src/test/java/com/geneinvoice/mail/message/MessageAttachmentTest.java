package com.geneinvoice.mail.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Exchange;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.IntegrationTestBase;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Files handed over with an email (§4.3): what is stored, what reaches Gmail, and what is refused.
 * The stand-in for Gmail is asked for the message it was given, so these assert on the bytes a
 * recipient's mail server would actually receive, not on our own record of them.
 */
class MessageAttachmentTest extends IntegrationTestBase {

    private static final String SEND = FakeGoogle.api("/messages/send");
    private static final String UPLOAD = "/upload/gmail/v1/users/me/messages/send";
    private static final byte[] PDF = "%PDF-1.4\nINV-0042\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    /** Bytes that are not text at all: the ones a 7-bit transfer would ruin. */
    private static final byte[] SCAN = scan();

    private long ceilingBefore;
    private int countBefore;
    private long jsonCeilingBefore;

    private static byte[] scan() {
        byte[] bytes = new byte[2048];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
        return bytes;
    }

    @BeforeEach
    void janeIsConnectedAndGmailAccepts() {
        ceilingBefore = properties.getSend().getMaxAttachmentBytes();
        countBefore = properties.getSend().getMaxAttachments();
        jsonCeilingBefore = properties.getSend().getMaxJsonSendBytes();
        connect("7", "Jane Doe", "jane@gmail.com");
        AtomicInteger sent = new AtomicInteger();
        google.on("POST", SEND, exchange -> {
            int n = sent.incrementAndGet();
            return new Reply(200, "{\"id\":\"gm-" + n + "\",\"threadId\":\"th-" + n + "\",\"labelIds\":[\"SENT\"]}");
        });
    }

    @AfterEach
    void putTheLimitsBack() {
        properties.getSend().setMaxAttachmentBytes(ceilingBefore);
        properties.getSend().setMaxAttachments(countBefore);
        properties.getSend().setMaxJsonSendBytes(jsonCeilingBefore);
    }

    // ---- requests ----------------------------------------------------------------------

    private static Map<String, Object> file(String filename, String contentType, byte[] content) {
        return map("filename", filename, "contentType", contentType,
                "content", Base64.getEncoder().encodeToString(content));
    }

    private static Map<String, Object> request(List<?> copies, Object... attachments) {
        return map("sender", map("ownerRef", "7", "name", "Jane Doe"),
                "subject", "Invoice INV-0042", "body", "The invoice is attached.", "groupRef", "91",
                "retry", false, "copies", copies, "attachments", List.of(attachments));
    }

    private static Map<String, Object> copyTo(String externalId, String name, String address) {
        return map("externalId", externalId, "to", map("name", name, "address", address));
    }

    // ---- what Gmail is given -----------------------------------------------------------

    /** The parts of the message the worker handed to Gmail, decoded the way Gmail would. */
    private MimeMultipart partsSent(int index) throws Exception {
        Exchange request = google.requests("POST", SEND).get(index);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(request.json().get("raw").asText())));
        assertThat(message.getContentType()).startsWith("multipart/mixed");
        return (MimeMultipart) message.getContent();
    }

    @Test
    void theFilesGoOutWithEveryCopyOfTheEmail() throws Exception {
        call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob Smith", "bob@acme.com"),
                        copyTo("gi-91-502", "Ravi Kumar", "ravi@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF),
                file("scan.bin", "application/octet-stream", SCAN)))
                .andExpect(status().isAccepted());

        work();

        assertThat(google.requests("POST", SEND)).hasSize(2);
        for (int copy = 0; copy < 2; copy++) {
            MimeMultipart parts = partsSent(copy);
            assertThat(parts.getCount()).isEqualTo(3);
            assertThat(parts.getBodyPart(0).getContent()).isEqualTo("The invoice is attached.");
            BodyPart invoice = parts.getBodyPart(1);
            assertThat(invoice.getFileName()).isEqualTo("INV-0042.pdf");
            assertThat(invoice.getContentType()).startsWith("application/pdf");
            assertThat(invoice.getDisposition()).isEqualToIgnoringCase(Part.ATTACHMENT);
            assertThat(invoice.getInputStream().readAllBytes()).isEqualTo(PDF);
            // Bytes that are not text come back byte for byte, which is what base64 is for.
            assertThat(parts.getBodyPart(2).getInputStream().readAllBytes()).isEqualTo(SCAN);
        }
        assertThat(message("gi-91-501").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message("gi-91-502").getStatus()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void theFilesAreStoredOnceForTheWholeEmailAndTheCopiesPointAtThem() throws Exception {
        call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob", "bob@acme.com"), copyTo("gi-91-502", "Ravi", "ravi@acme.com"),
                        copyTo("gi-91-503", "Ann", "ann@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF)))
                .andExpect(status().isAccepted());

        Long setId = message("gi-91-501").getAttachmentSetId();
        assertThat(setId).isNotNull();
        assertThat(message("gi-91-502").getAttachmentSetId()).isEqualTo(setId);
        assertThat(message("gi-91-503").getAttachmentSetId()).isEqualTo(setId);
        // Three copies, one stored file: the bytes are not written once per recipient.
        assertThat(attachmentSetRepository.findAll()).singleElement()
                .satisfies(set -> {
                    assertThat(set.getFileCount()).isEqualTo(1);
                    assertThat(set.getSizeBytes()).isEqualTo(PDF.length);
                    assertThat(set.getCreatedAt()).isEqualTo(T0);
                });
        assertThat(attachmentRepository.findBySetIdOrderByOrdinalAsc(setId)).singleElement()
                .satisfies(file -> {
                    assertThat(file.getFilename()).isEqualTo("INV-0042.pdf");
                    assertThat(file.getContentType()).isEqualTo("application/pdf");
                    assertThat(file.getSizeBytes()).isEqualTo(PDF.length);
                    // The bytes themselves are in the database, so a restart loses nothing.
                    assertThat(file.getContent()).isEqualTo(PDF);
                });
    }

    @Test
    void sendingTheSameFilesAgainReusesWhatIsStored() throws Exception {
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF))).andExpect(status().isAccepted());
        Long first = message("gi-91-501").getAttachmentSetId();

        // Another email of another group, carrying the very same file.
        call(post("/api/v1/messages"), map("sender", map("ownerRef", "7", "name", "Jane Doe"),
                "subject", "Reminder", "body", "Still outstanding.", "groupRef", "92", "retry", false,
                "copies", List.of(copyTo("gi-92-501", "Bob", "bob@acme.com")),
                "attachments", List.of(file("INV-0042.pdf", "application/pdf", PDF))))
                .andExpect(status().isAccepted());

        assertThat(message("gi-92-501").getAttachmentSetId()).isEqualTo(first);
        assertThat(attachmentSetRepository.findAll()).hasSize(1);
        assertThat(attachmentRepository.findAll()).hasSize(1);
    }

    @Test
    void aDifferentFileUnderTheSameNameIsStoredSeparately() throws Exception {
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF))).andExpect(status().isAccepted());
        call(post("/api/v1/messages"), map("sender", map("ownerRef", "7", "name", "Jane Doe"),
                "subject", "Corrected", "body", "New version.", "groupRef", "92", "retry", false,
                "copies", List.of(copyTo("gi-92-501", "Bob", "bob@acme.com")),
                "attachments", List.of(file("INV-0042.pdf", "application/pdf", SCAN))))
                .andExpect(status().isAccepted());

        assertThat(message("gi-92-501").getAttachmentSetId()).isNotEqualTo(message("gi-91-501").getAttachmentSetId());
        assertThat(attachmentSetRepository.findAll()).hasSize(2);
    }

    @Test
    void anEmailWithNoFilesCarriesNoneAndIsWrittenAsOnePart() throws Exception {
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-501", "Bob", "bob@acme.com")));

        work();

        assertThat(message("gi-91-501").getAttachmentSetId()).isNull();
        assertThat(attachmentSetRepository.findAll()).isEmpty();
        Exchange request = google.requests("POST", SEND).get(0);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(request.json().get("raw").asText())));
        assertThat(message.getContentType()).isEqualToIgnoringCase("text/plain; charset=UTF-8");
    }

    @Test
    void theNameAndTypeAreCleanedBeforeAnythingIsStoredOrSent() throws Exception {
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("  ../../etc/Rechnung\r\nBcc: someone@elsewhere.test  ", "application/pdf", PDF),
                file("odd.bin", "   ", PDF))).andExpect(status().isAccepted());

        List<MailAttachment> stored =
                attachmentRepository.findBySetIdOrderByOrdinalAsc(message("gi-91-501").getAttachmentSetId());
        assertThat(stored).extracting(MailAttachment::getFilename)
                .containsExactly("Rechnung Bcc: someone@elsewhere.test", "odd.bin");
        assertThat(stored.get(1).getContentType()).isEqualTo("application/octet-stream");

        work();

        MimeMultipart parts = partsSent(0);
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("Rechnung Bcc: someone@elsewhere.test");
        // The name never became a header of its own.
        assertThat(parts.getBodyPart(1).getHeader("Bcc")).isNull();
    }

    // ---- the ceiling -------------------------------------------------------------------

    @Test
    void filesOverTheCeilingAreRefusedWithTheNumberInTheMessage() throws Exception {
        // Small enough to test without holding 17 MiB in memory; the arithmetic is the same.
        properties.getSend().setMaxAttachmentBytes(4096);
        byte[] big = new byte[3000];

        JsonNode error = read(call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("a.bin", "application/octet-stream", big),
                file("b.bin", "application/octet-stream", big)))
                .andExpect(status().isBadRequest()));

        String message = error.get("fieldErrors").get("attachments").asText();
        assertThat(message).contains("too large").contains("4096 bytes").contains("25.0 MB");
        assertThat(error.get("message").asText()).isEqualTo(message);
        // Nothing was saved: the email is refused whole, not sent with some of its files.
        assertThat(messageRepository.findAll()).isEmpty();
        assertThat(attachmentSetRepository.findAll()).isEmpty();
    }

    @Test
    void aBodyTooBigToBeWorthDecodingIsRefusedOnItsEncodedSizeAlone() throws Exception {
        properties.getSend().setMaxAttachmentBytes(1024);

        JsonNode error = read(call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("a.bin", "application/octet-stream", new byte[20_000])))
                .andExpect(status().isBadRequest()));

        assertThat(error.get("fieldErrors").get("attachments").asText())
                .contains("too large").contains("1024 bytes");
    }

    @Test
    void exactlyTheCeilingIsAccepted() throws Exception {
        properties.getSend().setMaxAttachmentBytes(2048);

        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("a.bin", "application/octet-stream", new byte[2048]))).andExpect(status().isAccepted());

        assertThat(message("gi-91-501").getAttachmentSetId()).isNotNull();
    }

    @Test
    void moreFilesThanAllowedAreRefused() throws Exception {
        properties.getSend().setMaxAttachments(2);

        JsonNode error = read(call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("a.bin", "application/pdf", PDF), file("b.bin", "application/pdf", PDF),
                file("c.bin", "application/pdf", PDF))).andExpect(status().isBadRequest()));

        assertThat(error.get("fieldErrors").get("attachments").asText())
                .isEqualTo("At most 2 files can be attached to one email");
    }

    @Test
    void aFileWithNoNameNoBytesOrNoBase64IsRefusedByName() throws Exception {
        JsonNode error = read(call(post("/api/v1/messages"), request(
                List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                map("filename", "  ", "contentType", "application/pdf", "content", "AAAA"),
                map("filename", "empty.pdf", "contentType", "application/pdf", "content", ""),
                map("filename", "broken.pdf", "contentType", "application/pdf", "content", "A")))
                .andExpect(status().isBadRequest()));

        assertThat(objectMapper.convertValue(error.get("fieldErrors"), Map.class)).isEqualTo(Map.of(
                "attachments[0].filename", "Enter the file name",
                "attachments[1].content", "The file is empty",
                "attachments[2].content", "The file is not base64"));
        assertThat(messageRepository.findAll()).isEmpty();
    }

    // ---- how it reaches Google -----------------------------------------------------------

    @Test
    void aMessageTooBigForAJsonRequestGoesToGmailsUploadUriInstead() throws Exception {
        // A message over the JSON ceiling; the real one is 3 MiB, and the path taken is the same.
        properties.getSend().setMaxJsonSendBytes(1024);
        AtomicInteger uploads = new AtomicInteger();
        google.on("POST", UPLOAD, exchange -> {
            int n = uploads.incrementAndGet();
            return new Reply(200, "{\"id\":\"gm-u" + n + "\",\"threadId\":\"th-u" + n + "\"}");
        });
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("scan.bin", "application/octet-stream", SCAN))).andExpect(status().isAccepted());

        work();

        // Not as JSON: base64url inside a JSON body would make the request a third bigger again, and
        // Google refuses an ordinary request of about 5 MB.
        assertThat(google.requests("POST", SEND)).isEmpty();
        List<Exchange> sends = google.requests("POST", UPLOAD);
        assertThat(sends).hasSize(1);
        assertThat(sends.get(0).param("uploadType")).isEqualTo("media");
        assertThat(sends.get(0).header("Content-Type")).startsWith("message/rfc822");
        // The body is the message itself, not wrapped in anything.
        assertThat(sends.get(0).body()).startsWith("Date: ").contains("Content-Type: multipart/mixed");
        MimeMessage sent = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(sends.get(0).body().getBytes(StandardCharsets.ISO_8859_1)));
        MimeMultipart parts = (MimeMultipart) sent.getContent();
        assertThat(parts.getBodyPart(1).getInputStream().readAllBytes()).isEqualTo(SCAN);
        assertThat(message("gi-91-501").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message("gi-91-501").getProviderMessageId()).isEqualTo("gm-u1");
    }

    @Test
    void aSmallMessageStillGoesAsJsonTheWayItAlwaysHas() throws Exception {
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF))).andExpect(status().isAccepted());

        work();

        assertThat(google.requests("POST", UPLOAD)).isEmpty();
        assertThat(google.requests("POST", SEND)).hasSize(1);
    }

    // ---- what must never happen quietly -------------------------------------------------

    @Test
    void aCopyWhoseStoredFilesHaveGoneIsNotSentWithoutThem() throws Exception {
        call(post("/api/v1/messages"), request(List.of(copyTo("gi-91-501", "Bob", "bob@acme.com")),
                file("INV-0042.pdf", "application/pdf", PDF))).andExpect(status().isAccepted());
        attachmentRepository.deleteAll();

        work();

        assertThat(google.requests("POST", SEND)).isEmpty();
        MailMessage copy = message("gi-91-501");
        assertThat(copy.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(copy.getError()).isEqualTo(SendWorker.ATTACHMENTS_LOST);
    }
}
