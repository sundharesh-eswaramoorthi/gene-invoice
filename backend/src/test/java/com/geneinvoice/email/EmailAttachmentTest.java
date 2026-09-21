package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.document.DocumentVisibility;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attaching files that are already in the app to an email (E17): what a record offers, what it
 * accepts, and what the email keeps of it afterwards.
 *
 * <p>Nothing here looks at the wire. Whether a copy can carry the bytes is the mail service's
 * business and is changing; which documents this record will let a sender choose, and what is
 * written down when they do, is the app's own rule and is what these pin.
 */
class EmailAttachmentTest extends EmailTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;

    // ---- what is offered -------------------------------------------------------------

    @Test
    void theOfferIsTheRecordsOwnDocumentsThenItsCustomersAndNothingElse() throws Exception {
        Invoice inv = invoice(acme, sales);
        Invoice anotherOfTheSameCustomer = invoice(acme, sales);
        JsonNode onTheInvoice = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        // The customer's other invoice belongs to another conversation: its files are its own, even
        // though the customer — and so the person writing — is the same.
        upload(admin, "INVOICE", anotherOfTheSameCustomer.getId(), "other-po.pdf");
        JsonNode onTheCustomer = upload(admin, "CUSTOMER", acme.getId(), "msa.pdf");
        // A deleted file is not offered: the email would snapshot a name nobody can download.
        JsonNode deleted = upload(admin, "INVOICE", inv.getId(), "superseded-po.pdf");
        deleteDocument(deleted);

        JsonNode offered = getOk("/api/emails/attachable", admin,
                "entityType", "INVOICE", "entityId", inv.getId().toString());

        assertThat(filenames(offered)).containsExactly("po.pdf", "msa.pdf");
        assertThat(offered.get(0).get("id").asLong()).isEqualTo(onTheInvoice.get("id").asLong());
        assertThat(offered.get(0).get("source").asText()).isEqualTo("RECORD");
        assertThat(offered.get(0).get("sourceLabel").asText()).isEqualTo("This invoice");
        assertThat(offered.get(0).get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(offered.get(0).get("sizeBytes").asLong()).isEqualTo(onTheInvoice.get("sizeBytes").asLong());
        assertThat(offered.get(1).get("id").asLong()).isEqualTo(onTheCustomer.get("id").asLong());
        assertThat(offered.get(1).get("source").asText()).isEqualTo("CUSTOMER");
        assertThat(offered.get(1).get("sourceLabel").asText()).isEqualTo("Customer");

        // The other invoice is offered its own file and the same customer file, which is the rule
        // read from the other side rather than a special case for this one.
        assertThat(filenames(getOk("/api/emails/attachable", admin, "entityType", "INVOICE",
                "entityId", anotherOfTheSameCustomer.getId().toString())))
                .containsExactly("other-po.pdf", "msa.pdf");
    }

    @Test
    void aRecordThatKeepsNoDocumentsOfItsOwnIsOfferedItsCustomersAndOneWithoutACustomerIsOfferedNothing()
            throws Exception {
        upload(admin, "CUSTOMER", acme.getId(), "msa.pdf");
        actAs(admin);
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.now().plusDays(7),
                collections.getId(), null, null, null));

        // Promises carry no documents of their own, so the customer's are the whole offer (§4.5).
        assertThat(filenames(getOk("/api/emails/attachable", admin,
                "entityType", "PROMISE", "entityId", promise.id().toString())))
                .containsExactly("msa.pdf");
        // A product belongs to no customer, so there is nothing that could be on it or behind it.
        assertThat(getOk("/api/emails/attachable", admin,
                "entityType", "PRODUCT", "entityId", widget.getId().toString())).isEmpty();
    }

    @Test
    void aCallerWithoutTheDocumentPrivilegeIsOfferedNothingAndIsRefusedOneAskedForByIdAnyway()
            throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode document = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        User writer = user("wendy.writer", noDocumentsRole().getName());

        // They may still write the email; they simply have no files to choose from, so the compose
        // form shows them an empty section rather than refusing to open.
        assertThat(getOk("/api/emails/attachable", writer,
                "entityType", "INVOICE", "entityId", inv.getId().toString())).isEmpty();

        // Asking for one by id is another matter: the offer being empty is not a privilege check.
        postJson("/api/emails", writer, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(document.get("id").asLong())))
                .andExpect(status().isForbidden());
        assertThat(emailAttachmentRepository.count()).isZero();
        assertThat(emailRepository.count()).isZero();
    }

    // ---- what is attached ------------------------------------------------------------

    @Test
    void attachingByIdKeepsASnapshotOfEachFileAgainstTheEmailInTheOrderTheyWereChosen() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode onTheCustomer = upload(admin, "CUSTOMER", acme.getId(), "msa.pdf");
        JsonNode onTheInvoice = upload(admin, "INVOICE", inv.getId(), "po.pdf");

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                // The customer's file first, and the same file twice: a sender's order is kept, and
                // one document asked for twice is one attachment, not two copies of one file.
                "documentIds", List.of(onTheCustomer.get("id").asLong(), onTheInvoice.get("id").asLong(),
                        onTheCustomer.get("id").asLong())));

        long emailId = sent.get("id").asLong();
        assertThat(sent.get("attachments")).hasSize(2);
        assertThat(sent.at("/attachments/0/documentId").asLong()).isEqualTo(onTheCustomer.get("id").asLong());
        assertThat(sent.at("/attachments/0/filename").asText()).isEqualTo("msa.pdf");
        assertThat(sent.at("/attachments/0/contentType").asText()).isEqualTo("application/pdf");
        assertThat(sent.at("/attachments/0/sizeBytes").asLong())
                .isEqualTo(onTheCustomer.get("sizeBytes").asLong())
                .isNotEqualTo(onTheInvoice.get("sizeBytes").asLong());
        assertThat(sent.at("/attachments/1/filename").asText()).isEqualTo("po.pdf");

        List<EmailAttachment> rows = emailAttachmentRepository.findByEmailIdOrderByIdAsc(emailId);
        assertThat(rows).extracting(EmailAttachment::getEmailId).containsOnly(emailId);
        assertThat(rows).extracting(EmailAttachment::getDocumentId)
                .containsExactly(onTheCustomer.get("id").asLong(), onTheInvoice.get("id").asLong());
        assertThat(rows).extracting(EmailAttachment::getFilename).containsExactly("msa.pdf", "po.pdf");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getContentType()).isEqualTo("application/pdf");
            assertThat(r.getSizeBytes()).isPositive();
            assertThat(r.getCreatedAt()).isNotNull();
        });

        // The Email tab reads the same list back, on the record's page as well as one email.
        assertThat(getOk("/api/emails/" + emailId, admin).get("attachments")).hasSize(2);
        assertThat(getOk("/api/emails", admin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .at("/content/0/attachments")).hasSize(2);
    }

    @Test
    void whatWasAttachedStillReadsTheSameAfterTheDocumentItselfIsGone() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode document = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "documentIds", List.of(document.get("id").asLong())));

        // The snapshot is the point of copying the name, the type and the size onto the email: what
        // went out is history, and deleting the file afterwards must not rewrite it.
        deleteDocument(document);

        JsonNode reread = getOk("/api/emails/" + sent.get("id").asLong(), admin);
        assertThat(reread.at("/attachments/0/filename").asText()).isEqualTo("po.pdf");
        assertThat(reread.at("/attachments/0/documentId").asLong()).isEqualTo(document.get("id").asLong());
        assertThat(reread.at("/attachments/0/sizeBytes").asLong()).isEqualTo(document.get("sizeBytes").asLong());
    }

    @Test
    void anEmailWithNothingAttachedSaysSoAndWritesNoRows() throws Exception {
        Invoice inv = invoice(acme, sales);
        upload(admin, "INVOICE", inv.getId(), "po.pdf");

        // Almost every email attaches nothing; an absent list and an empty one mean the same thing.
        JsonNode withoutTheField = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));
        JsonNode withAnEmptyList = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "documentIds", List.of()));

        assertThat(withoutTheField.get("attachments")).isEmpty();
        assertThat(withAnEmptyList.get("attachments")).isEmpty();
        assertThat(emailAttachmentRepository.count()).isZero();
    }

    @Test
    void aPaymentAttachesItsOwnDocumentsAndItsCustomersJustAsAnInvoiceDoes() throws Exception {
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Bank transfer", null, List.of(), collections.getId(), null));
        JsonNode onThePayment = upload(admin, "PAYMENT", payment.getId(), "advice.pdf");
        JsonNode onTheCustomer = upload(admin, "CUSTOMER", acme.getId(), "msa.pdf");

        JsonNode offered = getOk("/api/emails/attachable", admin,
                "entityType", "PAYMENT", "entityId", payment.getId().toString());
        assertThat(filenames(offered)).containsExactly("advice.pdf", "msa.pdf");
        assertThat(offered.get(0).get("sourceLabel").asText()).isEqualTo("This payment");

        JsonNode sent = send(admin, email("PAYMENT", payment.getId(), List.of(toCustomer()),
                "documentIds", List.of(onThePayment.get("id").asLong(), onTheCustomer.get("id").asLong())));
        assertThat(sent.get("attachments")).hasSize(2);
    }

    // ---- what is refused -------------------------------------------------------------

    @Test
    void aDocumentOnAnotherRecordAndOneThatDoesNotExistAreRefusedInTheSameWords() throws Exception {
        Invoice inv = invoice(acme, sales);
        Invoice elsewhere = invoice(acme, sales);
        JsonNode anotherRecords = upload(admin, "INVOICE", elsewhere.getId(), "secret-po.pdf");
        long missing = anotherRecords.get("id").asLong() + 1000;

        // The same sentence for both, so nobody can tell a file they may not attach from one that
        // was never there — and, in particular, so no refusal ever names a file for them.
        String reason = " is not on Invoice " + inv.getInvoiceNumber() + " or its customer";
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(anotherRecords.get("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document #" + anotherRecords.get("id").asLong() + reason));
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(missing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document #" + missing + reason));

        // A preview is checked the same way, so a bad id is caught before Send rather than by it.
        postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(anotherRecords.get("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document #" + anotherRecords.get("id").asLong() + reason));

        // A refused send writes nothing at all: not the email, and not one good attachment of it.
        assertThat(emailRepository.count()).isZero();
        assertThat(emailAttachmentRepository.count()).isZero();
    }

    @Test
    void aDeletedDocumentOfThisRecordIsRefusedAsDeletedRatherThanAsAbsent() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode document = upload(admin, "INVOICE", inv.getId(), "po.pdf");
        deleteDocument(document);

        // It is this record's file and the sender knows it, so they are told what became of it
        // rather than that it never existed — the words that keep other people's files private are
        // needed only where the sender had no business seeing the file in the first place.
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(document.get("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Document #" + document.get("id").asLong() + " (po.pdf) has been deleted"));
    }

    @Test
    void moreDocumentsThanOneEmailMayCarryAreRefusedBeforeAnythingIsSaved() throws Exception {
        Invoice inv = invoice(acme, sales);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i <= EmailAttachments.MAX_ATTACHMENTS; i++) {
            ids.add(upload(admin, "INVOICE", inv.getId(), "po-" + i + ".pdf").get("id").asLong());
        }

        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("At most " + EmailAttachments.MAX_ATTACHMENTS
                        + " documents can be attached to one email"));
        assertThat(emailRepository.count()).isZero();

        // The cap counts documents, not ids: the same one asked for repeatedly is one attachment.
        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "documentIds", List.of(ids.get(0), ids.get(0), ids.get(0))));
        assertThat(sent.get("attachments")).hasSize(1);
    }

    /**
     * The cap on how much one email carries, which until now only the mail service applied. A
     * document is 10 MiB at most on its own ({@code app.documents.max-size-bytes}) and ten may be
     * attached, so a hundred megabytes passed every check the backend made; the mail service then
     * refused the submission with a 400, which is a <em>permanent</em> failure to {@code
     * MailServiceClient}. The email was already saved, so it failed for good, on a record, with the
     * compose form that chose the files long closed and no way to take one off.
     *
     * <p>Refusing here instead puts the refusal in front of the person choosing the files, and says
     * the same two numbers the mail service would have said.
     */
    @Test
    void filesTotallingMoreThanTheMailServiceWillCarryAreRefusedWhileTheSenderCanStillFixIt()
            throws Exception {
        Invoice inv = invoice(acme, sales);
        long first = uploadOfSize(inv, "scan-1.pdf", NINE_MIB);
        long second = uploadOfSize(inv, "scan-2.pdf", NINE_MIB);
        String tooLarge = "The attachments are too large: 18.9 MB in all, and at most 17.8 MB ("
                + EmailAttachments.MAX_ATTACHMENT_BYTES + " bytes) can be sent with one email";

        // Either file on its own is well within the cap, so what is refused below is the total —
        // the thing nothing was measuring — and not any one file.
        JsonNode one = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "documentIds", List.of(first)));
        assertThat(one.get("attachments")).hasSize(1);

        // The preview refuses it, which is where the sender is still choosing (E18)...
        postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(first, second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(tooLarge));
        // ...and so does the send, for a client that took the ids straight to it.
        postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "documentIds", List.of(first, second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(tooLarge));

        // Nothing of the refused email was written: no second email, and no second attachment list.
        assertThat(emailRepository.count()).isEqualTo(1);
        assertThat(emailAttachmentRepository.count()).isEqualTo(1);
    }

    // ---- a customer login ------------------------------------------------------------

    @Test
    void aCustomerLoginIsOfferedOnlySharedDocumentsAndIsRefusedAnInternalOneInTheSameWordsAsAMissingOne()
            throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);
        JsonNode sharedOnInvoice = upload(admin, "INVOICE", inv.getId(), "signed-po.pdf", DocumentVisibility.SHARED);
        JsonNode internalOnInvoice = upload(admin, "INVOICE", inv.getId(), "margin-notes.pdf");
        JsonNode sharedOnCustomer = upload(admin, "CUSTOMER", acme.getId(), "msa.pdf", DocumentVisibility.SHARED);
        upload(admin, "CUSTOMER", acme.getId(), "credit-review.pdf");

        assertThat(filenames(getOk("/api/emails/attachable", acmeLogin,
                "entityType", "INVOICE", "entityId", inv.getId().toString())))
                .containsExactly("signed-po.pdf", "msa.pdf");

        // Staff see all four on the same record, so the short list is the customer's visibility and
        // not the record having fewer files (D7).
        assertThat(getOk("/api/emails/attachable", admin,
                "entityType", "INVOICE", "entityId", inv.getId().toString())).hasSize(4);

        JsonNode sent = send(acmeLogin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC")),
                "from", toUser(acmeLogin),
                "documentIds", List.of(sharedOnInvoice.get("id").asLong(), sharedOnCustomer.get("id").asLong())));
        assertThat(sent.get("attachments")).hasSize(2);

        // An internal file must not even be confirmed to exist, so it is refused in the same words
        // an id that was never a document gets.
        long missing = internalOnInvoice.get("id").asLong() + 1000;
        String reason = " is not on Invoice " + inv.getInvoiceNumber() + " or its customer";
        postJson("/api/emails", acmeLogin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "from", toUser(acmeLogin),
                        "documentIds", List.of(internalOnInvoice.get("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Document #" + internalOnInvoice.get("id").asLong() + reason));
        postJson("/api/emails", acmeLogin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                        "from", toUser(acmeLogin), "documentIds", List.of(missing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document #" + missing + reason));
    }

    @Test
    void aCustomerLoginIsNeverToldTheNameOfAnotherCustomersFile() throws Exception {
        Invoice ours = invoice(acme, sales);
        Customer globex = customer("Globex Corp", "ap@globex.test");
        Invoice theirs = invoice(globex, sales);
        // Shared, so nothing about the document itself hides it: only whose it is does.
        JsonNode theirFile = upload(admin, "INVOICE", theirs.getId(), "globex-rates.pdf",
                DocumentVisibility.SHARED);

        postJson("/api/emails", acmeLogin, email("INVOICE", ours.getId(), List.of(toCustomer()),
                        "from", toUser(acmeLogin),
                        "documentIds", List.of(theirFile.get("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document #" + theirFile.get("id").asLong()
                        + " is not on Invoice " + ours.getInvoiceNumber() + " or its customer"));
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Nine mebibytes, so two files pass every per-file check (a document is capped at ten) and
     * only their total is over the mail service's 17 MiB.
     */
    private static final int NINE_MIB = 9 * 1024 * 1024;

    /** A real upload of a given size, for the checks that are about how big an email is. */
    private long uploadOfSize(Invoice inv, String filename, int sizeBytes) throws Exception {
        JsonNode document = read(mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf",
                                pdf(filename, sizeBytes)))
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(inv.getId()))
                        .param("visibility", DocumentVisibility.INTERNAL.name())
                        .with(as(admin)))
                .andExpect(status().isCreated()));
        assertThat(document.get("sizeBytes").asLong()).isEqualTo(sizeBytes);
        return document.get("id").asLong();
    }

    private JsonNode upload(User as, String entityType, Long entityId, String filename) throws Exception {
        return upload(as, entityType, entityId, filename, DocumentVisibility.INTERNAL);
    }

    /** Uploads a PDF through the real endpoint, so the offer reads rows the app itself wrote. */
    private JsonNode upload(User as, String entityType, Long entityId, String filename,
                            DocumentVisibility visibility) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/documents");
        return read(mockMvc.perform(request
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdf(filename)))
                        .param("entityType", entityType)
                        .param("entityId", String.valueOf(entityId))
                        .param("visibility", visibility.name())
                        .with(as(as)))
                .andExpect(status().isCreated()));
    }

    private void deleteDocument(JsonNode document) throws Exception {
        mockMvc.perform(delete("/api/documents/" + document.get("id").asLong()).with(as(admin)))
                .andExpect(status().isNoContent());
    }

    /** The same PDF padded out to a given length; the header is what the app sniffs (AC-C7). */
    private static byte[] pdf(String filename, int sizeBytes) {
        byte[] header = pdf(filename);
        byte[] padded = Arrays.copyOf(header, Math.max(header.length, sizeBytes));
        Arrays.fill(padded, header.length, padded.length, (byte) ' ');
        return padded;
    }

    /** A PDF whose length is its name's, so every file in a test has a size of its own. */
    private static byte[] pdf(String filename) {
        return ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n" + filename + "\ntrailer\n%%EOF\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> filenames(JsonNode offered) {
        return StreamSupport.stream(offered.spliterator(), false)
                .map(d -> d.get("filename").asText()).toList();
    }

    /** May write email about an invoice, and holds no document privilege at all. */
    private Role noDocumentsRole() {
        return roleRepository.findByName("EMAIL_ONLY").orElseGet(() ->
                roleRepository.save(Role.builder().name("EMAIL_ONLY")
                        .description("Writes email, reads no documents")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName(Privileges.CUSTOMER_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.INVOICE_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.SCOPE_OVERRIDE).orElseThrow(),
                                privilegeRepository.findByName(Privileges.EMAIL_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.EMAIL_SEND).orElseThrow())))
                        .build()));
    }
}
