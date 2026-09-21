package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.transport.AttachmentPart;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the attached files become on the way out (E18). {@link EmailAttachmentTest} pins which
 * documents a record will let a sender choose and what the email records of that choice; this pins
 * the step after it — the dispatcher reading those documents back out of storage and handing the
 * bytes to the transport, so the mail service has something to put in the message.
 */
class EmailAttachmentWireTest extends EmailTestBase {

    @Autowired EmailDispatcher dispatcher;

    @BeforeEach
    void theMailServiceAccepts() {
        mailTransport.mode(Mode.SUCCESS);
    }

    /** A PDF whose bytes are its own name's, so no two files in a test look alike. */
    private static byte[] pdf(String filename) {
        return ("%PDF-1.7\n" + filename + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode upload(User as, String entityType, Long entityId, String filename) throws Exception {
        return read(mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf(filename)))
                        .param("entityType", entityType)
                        .param("entityId", String.valueOf(entityId))
                        .with(as(as)))
                .andExpect(status().isCreated()));
    }

    private Submission handedOver() {
        assertThat(mailTransport.submissions()).hasSize(1);
        return mailTransport.submissions().get(0);
    }

    @Test
    void theFilesTheSenderChoseAreHandedOverWithTheirBytes() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode onTheCustomer = upload(admin, "CUSTOMER", acme.getId(), "msa.pdf");
        JsonNode onTheInvoice = upload(admin, "INVOICE", inv.getId(), "po.pdf");

        send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()), "documentIds",
                List.of(onTheCustomer.get("id").asLong(), onTheInvoice.get("id").asLong())));

        List<AttachmentPart> files = handedOver().attachments();
        // In the order the sender chose them, and with what the email recorded about each.
        assertThat(files).extracting(AttachmentPart::filename).containsExactly("msa.pdf", "po.pdf");
        assertThat(files).extracting(AttachmentPart::contentType).containsOnly("application/pdf");
        assertThat(files.get(0).content()).isEqualTo(pdf("msa.pdf"));
        assertThat(files.get(1).content()).isEqualTo(pdf("po.pdf"));
        assertThat(files.get(1).sizeBytes()).isEqualTo(pdf("po.pdf").length);
    }

    @Test
    void anEmailWithNothingAttachedHandsOverNoFiles() throws Exception {
        Invoice inv = invoice(acme, sales);
        upload(admin, "INVOICE", inv.getId(), "po.pdf");

        send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        assertThat(handedOver().attachments()).isEmpty();
    }

    @Test
    void everyCopyOfOneEmailIsHandedOverWithTheSameFilesOnce() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode document = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        seat(acme, PocType.COLLECTION, collections);

        send(admin, email("INVOICE", inv.getId(), List.of(toUser(sales), toUser(collections)),
                "documentIds", List.of(document.get("id").asLong())));

        Submission submission = handedOver();
        assertThat(submission.copies()).hasSize(2);
        // The files hang off the email, not off each copy: one read, one list, every recipient.
        assertThat(submission.attachments()).singleElement()
                .satisfies(file -> assertThat(file.content()).isEqualTo(pdf("po.pdf")));
    }

    @Test
    void anEmailWhoseFileCannotBeReadBackIsNotSentWithoutIt() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode document = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "documentIds", List.of(document.get("id").asLong())));
        long emailId = sent.get("id").asLong();
        // The document row is gone from under the snapshot, so its bytes cannot be found again.
        mailTransport.reset();
        mailTransport.mode(Mode.SUCCESS);
        documentRepository.deleteAll();
        assertThat(emailAttachmentRepository.count()).isOne();
        emailRepository.findById(emailId).ifPresent(e -> {
            e.setStatus(EmailStatus.QUEUED);
            e.setHandedOffAt(null);
            emailRepository.save(e);
        });
        emailRecipientRepository.findByEmailIdOrderByIdAsc(emailId).forEach(r -> {
            r.setDeliveryStatus(RecipientDeliveryStatus.QUEUED);
            emailRecipientRepository.save(r);
        });

        dispatcher.dispatch(emailId);

        assertThat(mailTransport.submissions()).isEmpty();
        Email email = emailRepository.findById(emailId).orElseThrow();
        assertThat(email.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(email.getError()).startsWith(EmailDispatcher.ATTACHMENT_UNREADABLE);
    }
}
