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

    @Test
    void theContainersCeilingIsTheConfiguredLimitItself() {
        assertThat(multipartProperties.getMaxFileSize().toBytes())
                .isEqualTo(documentProperties.getMaxSizeBytes());
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

            String key = documentRepository.findById(dto.get("id").asLong()).orElseThrow().getStorageKey();
            assertThat(key).as(name.sent())
                    .doesNotContain("..")
                    .startsWith("invoice/" + acmeInvoice.getId() + "/");
        }
    }

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
        String name = "x" + "\uD83D\uDCC4".repeat(200) + ".pdf";

        String cleaned = DocumentRules.cleanFilename(name);

        assertThat(cleaned).hasSize(259);
        assertThat(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1))).isFalse();
        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(130);
    }

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
