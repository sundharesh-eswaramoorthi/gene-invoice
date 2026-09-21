package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.mailservice.MailServiceDtos.SubmitRequest;
import com.geneinvoice.email.transport.AttachmentPart;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.Submission;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body of {@code POST /api/v1/messages} as the mail service reads it. The two services are
 * built and deployed separately, so the field names and the encoding are a contract, not an
 * implementation detail: the service's {@code SubmitRequest.Attachment} is
 * {@code (filename, contentType, content)} with the file base64-encoded, and a rename on either
 * side has to fail here rather than in production.
 */
class SubmitRequestTest {

    private final ObjectMapper json = new ObjectMapper();

    private static final byte[] PDF = "%PDF-1.7 INV-0042".getBytes(StandardCharsets.UTF_8);

    private static Submission submission(List<AttachmentPart> attachments) {
        return new Submission(7, "Jane Doe", "Invoice INV-0042", "Please pay", "91", true,
                List.of(new CopyRequest("gi-91-501", "Bob Smith", "bob@acme.test")), attachments);
    }

    @Test
    void theFilesGoOutAsBase64UnderTheNamesTheServiceReads() throws Exception {
        JsonNode body = json.valueToTree(SubmitRequest.of(submission(List.of(
                new AttachmentPart("INV-0042.pdf", "application/pdf", PDF),
                new AttachmentPart("notes.txt", "text/plain", "Zoë".getBytes(StandardCharsets.UTF_8))))));

        JsonNode files = body.get("attachments");
        assertThat(files).hasSize(2);
        assertThat(files.get(0).get("filename").asText()).isEqualTo("INV-0042.pdf");
        assertThat(files.get(0).get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(files.get(0).get("content").asText())).isEqualTo(PDF);
        assertThat(files.get(1).get("filename").asText()).isEqualTo("notes.txt");
        assertThat(Base64.getDecoder().decode(files.get(1).get("content").asText()))
                .isEqualTo("Zoë".getBytes(StandardCharsets.UTF_8));
        // Everything the body carried before is still where it was.
        assertThat(body.get("sender").get("ownerRef").asText()).isEqualTo("7");
        assertThat(body.get("copies").get(0).get("to").get("address").asText()).isEqualTo("bob@acme.test");
    }

    @Test
    void anEmailWithNothingAttachedSendsAnEmptyList() throws Exception {
        JsonNode body = json.valueToTree(SubmitRequest.of(submission(List.of())));

        assertThat(body.get("attachments")).isEmpty();
    }

    @Test
    void aFilesContentsAreNeverPrinted() {
        String printed = new MailServiceDtos.Attachment("INV-0042.pdf", "application/pdf",
                Base64.getEncoder().encodeToString(PDF)).toString();

        assertThat(printed).contains("INV-0042.pdf").contains("application/pdf")
                .doesNotContain(Base64.getEncoder().encodeToString(PDF));
    }
}
