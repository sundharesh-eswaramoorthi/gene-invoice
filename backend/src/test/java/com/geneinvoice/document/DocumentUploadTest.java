package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.ApiError;
import com.geneinvoice.payment.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What may be attached and what is made of it (§4.3): the three kinds of record, the allow-list
 * read from the bytes rather than from what the client claimed, the size limit, and the filename,
 * which is cleaned for display and never used as a path (AC-C6 to AC-C9).
 */
class DocumentUploadTest extends DocumentTestBase {

    @Test
    void aFileCanBeAttachedToACustomerAnInvoiceAndAPayment() throws Exception {
        Payment payment = payment(acme, "500.00");

        JsonNode onCustomer = upload(admin, "CUSTOMER", acme.getId(), "trade-licence.pdf");
        JsonNode onInvoice = upload(admin, "INVOICE", acmeInvoice.getId(), "purchase-order.pdf");
        JsonNode onPayment = upload(admin, "PAYMENT", payment.getId(), "cheque.pdf");

        assertThat(onCustomer.get("entityLabel").asText()).isEqualTo("Customer Acme Ltd");
        assertThat(onCustomer.get("entityLink").asText()).isEqualTo("/customers/" + acme.getId());
        assertThat(onInvoice.get("entityLabel").asText())
                .isEqualTo("Invoice " + acmeInvoice.getInvoiceNumber());
        assertThat(onInvoice.get("entityLink").asText()).isEqualTo("/invoices/" + acmeInvoice.getId());
        assertThat(onPayment.get("entityLabel").asText()).isEqualTo("Payment #" + payment.getId());
        assertThat(onPayment.get("entityLink").asText()).isEqualTo("/payments/" + payment.getId());

        // Each record counts its own, which is what the tab's badge shows (AC-C1).
        for (JsonNode uploaded : List.of(onCustomer, onInvoice, onPayment)) {
            mockMvc.perform(get("/api/documents/count")
                            .param("entityType", uploaded.get("entityType").asText())
                            .param("entityId", uploaded.get("entityId").asText())
                            .with(as(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1));
        }
    }

    @Test
    void anUploadIsStoredWithWhatWasUploadedAndWhoUploadedIt() throws Exception {
        JsonNode dto = upload(cashier, "INVOICE", acmeInvoice.getId(), "purchase-order.pdf");

        Document stored = documentRepository.findById(dto.get("id").asLong()).orElseThrow();
        assertThat(stored.getEntityType()).isEqualTo(DocumentEntityType.INVOICE);
        assertThat(stored.getEntityId()).isEqualTo(acmeInvoice.getId());
        assertThat(stored.getCustomerId()).isEqualTo(acme.getId());
        assertThat(stored.getFilename()).isEqualTo("purchase-order.pdf");
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getSizeBytes()).isEqualTo(pdf().length);
        assertThat(stored.getChecksum()).hasSize(64);
        assertThat(stored.getVisibility()).isEqualTo(DocumentVisibility.INTERNAL);
        assertThat(stored.getUploadedByUserId()).isEqualTo(cashier.getId());
        assertThat(stored.getUploadedByName()).isEqualTo("Default Cashier");
        assertThat(stored.isDeleted()).isFalse();

        // The key is the server's own, and no byte of the file is in the database (AC-C16).
        assertThat(stored.getStorageKey())
                .startsWith("invoice/" + acmeInvoice.getId() + "/")
                .endsWith(".pdf")
                .doesNotContain("purchase-order");
        assertThat(dto.get("sizeLabel").asText()).isEqualTo(stored.getSizeBytes() + " B");
        assertThat(dto.at("/uploadedBy/name").asText()).isEqualTo("Default Cashier");
        assertThat(dto.at("/uploadedBy/userId").asLong()).isEqualTo(cashier.getId());
    }

    @Test
    void everyAllowedKindIsRecognisedFromItsOwnBytes() throws Exception {
        record Kind(String filename, byte[] bytes, String type, String extension) {}
        List<Kind> kinds = List.of(
                new Kind("po.pdf", pdf(), "application/pdf", ".pdf"),
                new Kind("scan.png", png(), "image/png", ".png"),
                new Kind("cheque.jpg", jpeg(), "image/jpeg", ".jpg"),
                new Kind("contract.docx", docx(),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
                new Kind("ledger.xlsx", xlsx(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"));

        for (Kind kind : kinds) {
            JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                            part(kind.filename(), kind.bytes(), "application/octet-stream")))
                    .andExpect(status().isCreated()));
            assertThat(dto.get("contentType").asText()).as(kind.filename()).isEqualTo(kind.type());
            assertThat(documentRepository.findById(dto.get("id").asLong()).orElseThrow().getStorageKey())
                    .as(kind.filename()).endsWith(kind.extension());
        }
    }

    @Test
    void whatTheClientCallsTheFileIsIgnoredAndTheBytesDecide() throws Exception {
        // A PNG sent as a PDF is stored as a PNG; a text file sent as a PDF is refused (AC-C7).
        JsonNode lying = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("actually-a-png.pdf", png(), "application/pdf")))
                .andExpect(status().isCreated()));
        assertThat(lying.get("contentType").asText()).isEqualTo("image/png");

        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("notes.pdf", plainText(), "application/pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(DocumentRules.DISALLOWED));
    }

    @Test
    void aZipThatIsNotAnOfficeFileIsRefused() throws Exception {
        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("evidence.zip", plainZip(), "application/zip")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(DocumentRules.DISALLOWED));
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void aFileOverTheLimitIsTheAppsOwnValidationError() throws Exception {
        byte[] tooBig = new byte[(int) documentProperties.getMaxSizeBytes() + 1];
        System.arraycopy(pdf(), 0, tooBig, 0, pdf().length);

        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("huge.pdf", tooBig, "application/pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors.file").value("The file is larger than 10 MB"));
        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void anUploadWithNoFileSaysSo() throws Exception {
        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("empty.pdf", new byte[0], "application/pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(DocumentRules.NO_FILE));

        mockMvc.perform(multipart("/api/documents")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(DocumentRules.NO_FILE));
    }

    /**
     * A part over the container's own limit is refused before any controller is reached, so it is
     * answered by the advice rather than by {@link DocumentRules} — in the same words and the same
     * shape, so a user never sees two different messages for one limit (AC-C9).
     */
    @Test
    void theContainersOwnLimitAnswersInTheSameWords() {
        DocumentUploadAdvice advice = new DocumentUploadAdvice(documentProperties);

        ResponseEntity<ApiError> answer = advice.tooLarge(
                new MaxUploadSizeExceededException(documentProperties.getMaxSizeBytes()),
                new MockHttpServletRequest("POST", "/api/documents"));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getBody()).isNotNull();
        assertThat(answer.getBody().fieldErrors())
                .containsEntry("file", "The file is larger than 10 MB");
    }

    /**
     * And the two limits are one setting rather than two numbers that happen to match today: a
     * deployment that raises {@code DOCUMENT_MAX_BYTES} raises the container's ceiling with it,
     * so the message above is always the limit that actually fired (§4.3, AC-C9).
     */
    @Test
    void theContainersCeilingIsTheConfiguredLimitItself() {
        assertThat(multipartProperties.getMaxFileSize().toBytes())
                .isEqualTo(documentProperties.getMaxSizeBytes());
        // The whole form is a little larger than the file it carries.
        assertThat(multipartProperties.getMaxRequestSize().toBytes())
                .isGreaterThan(documentProperties.getMaxSizeBytes());
    }

    @Test
    void anUnknownEntityTypeIsABadRequest() throws Exception {
        mockMvc.perform(uploadRequest(admin, "PROMISE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("entityType must be one of [CUSTOMER, INVOICE, PAYMENT]"));
    }

    /**
     * A null byte in a note is not a conflict with anything: it is a character that cannot be
     * stored, and it goes the way a filename's does. Postgres refuses it in a text column, and
     * letting it through turned an upload into "This change conflicts with existing data" (DOC-4).
     */
    @Test
    void aDescriptionKeepsItsLinesAndLosesWhatCannotBeStored() throws Exception {
        JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf"))
                        .param("description", "TRI null byte\nsigned by the customer"))
                .andExpect(status().isCreated()));

        assertThat(dto.get("description").asText()).isEqualTo("TRI nullbyte\nsigned by the customer");
        assertThat(documentRepository.findById(dto.get("id").asLong()).orElseThrow().getDescription())
                .isEqualTo("TRI nullbyte\nsigned by the customer");
    }

    @Test
    void aDescriptionLongerThanTheColumnIsRefused() throws Exception {
        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf"))
                        .param("description", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.description")
                        .value("must be at most 500 characters"));
        assertThat(documentRepository.count()).isZero();
    }

    // ---- filenames (AC-C8) -----------------------------------------------------

    @Test
    void aFilenameThatTriesToBeAPathKeepsOnlyItsLastName() throws Exception {
        record Name(String sent, String shown) {}
        List<Name> names = List.of(
                new Name("../../../etc/passwd", "passwd"),
                new Name("..\\..\\windows\\system32\\evil.pdf", "evil.pdf"),
                new Name("/etc/shadow", "shadow"),
                new Name("C:\\Users\\bob\\po.pdf", "po.pdf"),
                new Name("in\u0000voice.pdf", "invoice.pdf"),
                new Name("line\nbreak.pdf", "linebreak.pdf"),
                new Name("..", DocumentRules.UNNAMED),
                new Name("   ", DocumentRules.UNNAMED));

        for (Name name : names) {
            JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                            part(name.sent(), pdf(), "application/pdf")))
                    .andExpect(status().isCreated()));
            assertThat(dto.get("filename").asText()).as(name.sent()).isEqualTo(name.shown());

            // Whatever was sent, the bytes went to a generated key well inside the root.
            String key = documentRepository.findById(dto.get("id").asLong()).orElseThrow().getStorageKey();
            assertThat(key).as(name.sent())
                    .doesNotContain("..")
                    .startsWith("invoice/" + acmeInvoice.getId() + "/");
        }
    }

    /**
     * A name is one line of text that says what the file is. Characters that reorder it or take up
     * no room say nothing and hide what does: {@code invoice}, U+202E, {@code fdp.exe.pdf} is an
     * executable that reads as a PDF in the documents tab and, worse, in the name the browser
     * saves it under. They are dropped where the control characters are, so the name that is
     * stored is the name that is shown and the name that is downloaded (DOC-6, AC-C8).
     */
    @Test
    void aFilenameLosesTheCharactersThatReorderOrHideIt() throws Exception {
        record Name(String what, String sent, String shown) {}
        List<Name> names = List.of(
                new Name("right-to-left override", "invoice‮fdp.exe.pdf", "invoicefdp.exe.pdf"),
                new Name("zero width space", "in​voice.pdf", "invoice.pdf"),
                new Name("both directional overrides", "a‭‮b.pdf", "ab.pdf"),
                new Name("byte order mark", "﻿report.pdf", "report.pdf"),
                new Name("soft hyphen", "state­ment.pdf", "statement.pdf"),
                new Name("line separator", "line break.pdf", "linebreak.pdf"),
                new Name("nothing else at all", "‮​", DocumentRules.UNNAMED));

        for (Name name : names) {
            JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                            part(name.sent(), pdf(), "application/pdf")))
                    .andExpect(status().isCreated()));
            assertThat(dto.get("filename").asText()).as(name.what()).isEqualTo(name.shown());
        }
    }

    /**
     * And so the header a browser reads the name from carries nothing of them either — neither in
     * the ASCII fallback nor in the encoded form clients prefer, which is where they survived.
     */
    @Test
    void aReorderedNameReachesTheDownloadHeaderCleaned() throws Exception {
        JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("invoice‮fdp.exe.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/documents/" + dto.get("id").asLong() + "/download").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"invoicefdp.exe.pdf\"; "
                                + "filename*=UTF-8''invoicefdp.exe.pdf"));
    }

    @Test
    void anOverLongFilenameIsCutToTheColumn() throws Exception {
        JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("a".repeat(400) + ".pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated()));

        assertThat(dto.get("filename").asText()).hasSize(260).matches("a+");
        assertThat(documentRepository.findById(dto.get("id").asLong()).orElseThrow().getFilename())
                .hasSize(260);
    }

    @Test
    void aFilenameIsNeverCutBetweenTheHalvesOfOneCharacter() {
        // One letter and then 200 emoji: the cut at 260 would land inside the 130th, so it goes whole.
        String name = "x" + "\uD83D\uDCC4".repeat(200) + ".pdf";

        String cleaned = DocumentRules.cleanFilename(name);

        assertThat(cleaned).hasSize(259);
        assertThat(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1))).isFalse();
        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(130);
    }

    // ---- the audit trail (AC-C4) -----------------------------------------------

    @Test
    void anUploadIsInTheRecordsHistory() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "purchase-order.pdf");

        JsonNode history = read(mockMvc.perform(get("/api/audit")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .with(as(admin)))
                .andExpect(status().isOk()));

        JsonNode entry = StreamSupport.stream(history.spliterator(), false)
                .filter(e -> "DOCUMENT_UPLOADED".equals(e.get("action").asText()))
                .findFirst().orElseThrow();
        assertThat(entry.get("entityId").asLong()).isEqualTo(acmeInvoice.getId());
        assertThat(entry.get("changedByUserId").asLong()).isEqualTo(admin.getId());
        assertThat(entry.get("afterJson").asText())
                .contains("purchase-order.pdf")
                .contains("\"id\":" + dto.get("id").asLong());
    }
}
