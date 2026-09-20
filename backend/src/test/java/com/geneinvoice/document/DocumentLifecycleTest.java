package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A document's life after the upload: listing it, downloading it, editing it, deleting it, and
 * what happens to it when its record or its storage does not hold up (AC-C2 to AC-C5, AC-C13,
 * AC-C18).
 */
class DocumentLifecycleTest extends DocumentTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    // ---- listing (AC-C2) -------------------------------------------------------

    @Test
    void aRecordsDocumentsArePagedNewestFirst() throws Exception {
        for (int i = 1; i <= 11; i++) {
            upload(admin, "INVOICE", acmeInvoice.getId(), "po-" + i + ".pdf");
        }

        JsonNode first = read(mockMvc.perform(get("/api/documents")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .param("size", "10")
                        .with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(first.get("totalElements").asInt()).isEqualTo(11);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("content")).hasSize(10);
        assertThat(first.at("/content/0/filename").asText()).isEqualTo("po-11.pdf");

        JsonNode second = read(mockMvc.perform(get("/api/documents")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .param("page", "1")
                        .param("size", "10")
                        .with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.at("/content/0/filename").asText()).isEqualTo("po-1.pdf");

        // One record's documents never leak into another's.
        JsonNode elsewhere = read(mockMvc.perform(get("/api/documents")
                        .param("entityType", "CUSTOMER")
                        .param("entityId", String.valueOf(acme.getId()))
                        .with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(elsewhere.get("totalElements").asInt()).isZero();
    }

    // ---- downloading (AC-C13) --------------------------------------------------

    @Test
    void aDownloadStreamsTheBytesWithHeadersThatKeepTheBrowserOutOfThem() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "purchase order.pdf");

        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/documents/" + dto.get("id").asLong() + "/download").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"purchase order.pdf\"; filename*=UTF-8''purchase%20order.pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).isEqualTo(pdf());
        assertThat(response.getContentLength()).isEqualTo(pdf().length);
    }

    @Test
    void aNameThatIsNotAsciiIsGivenBothWays() throws Exception {
        JsonNode dto = read(mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("बिल \"2026\".pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/documents/" + dto.get("id").asLong() + "/download").with(as(admin)))
                .andExpect(status().isOk())
                // The quoted name is ASCII and cannot break out of the header; the real one follows.
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"___ _2026_.pdf\"; "
                                + "filename*=UTF-8''%E0%A4%AC%E0%A4%BF%E0%A4%B2%20%222026%22.pdf"));
    }

    // ---- editing ---------------------------------------------------------------

    @Test
    void aDescriptionKeepsItsLinesAndLosesWhatWouldReorderThem() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = dto.get("id").asLong();

        // A note is shown beside its file, so a character that reorders what it says spoofs the tab
        // exactly as it would the name (DOC-6): U+202E turns "fdp.exe" round. The line breaks and
        // tabs somebody typed are what a note is written with, and they stay.
        JsonNode edited = read(mockMvc.perform(patch("/api/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("description",
                                "Signed\tcopy\nsee \u202Efdp.exe\u200B and \uFEFFpage 2")))
                        .with(as(admin)))
                .andExpect(status().isOk()));

        String description = edited.get("description").asText();
        assertThat(description).isEqualTo("Signed\tcopy\nsee fdp.exe and page 2");
        assertThat(description).doesNotContain("\u202E").doesNotContain("\u200B").doesNotContain("\uFEFF");
    }

    @Test
    void theDescriptionAndVisibilityCanBeChangedAndTheChangeIsAudited() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = dto.get("id").asLong();

        JsonNode edited = read(mockMvc.perform(patch("/api/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Signed by the customer\",\"visibility\":\"SHARED\"}")
                        .with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(edited.get("description").asText()).isEqualTo("Signed by the customer");
        assertThat(edited.get("visibility").asText()).isEqualTo("SHARED");

        // A field left out is left alone.
        JsonNode again = read(mockMvc.perform(patch("/api/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Signed and stamped\"}")
                        .with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(again.get("description").asText()).isEqualTo("Signed and stamped");
        assertThat(again.get("visibility").asText()).isEqualTo("SHARED");

        // Two edits, newest first; the one that shared it is the older.
        List<JsonNode> edits = historyEntries("INVOICE", acmeInvoice.getId(), "DOCUMENT_UPDATED");
        assertThat(edits).hasSize(2);
        JsonNode shared = edits.get(1);
        assertThat(shared.get("beforeJson").asText()).contains("INTERNAL");
        assertThat(shared.get("afterJson").asText()).contains("SHARED");
    }

    @Test
    void anUnknownVisibilityIsABadRequest() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");

        mockMvc.perform(patch("/api/documents/" + dto.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\"}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("visibility must be one of [INTERNAL, SHARED]"));
    }

    // ---- deleting (AC-C3) ------------------------------------------------------

    @Test
    void deletingIsSoftAndTheFileStopsBeingDownloadable() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = dto.get("id").asLong();
        Path bytes = storedPath(id);

        mockMvc.perform(delete("/api/documents/" + id).with(as(admin)))
                .andExpect(status().isNoContent());

        Document row = documentRepository.findById(id).orElseThrow();
        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getDeletedByUserId()).isEqualTo(admin.getId());
        assertThat(row.getDeletedAt()).isNotNull();
        // The row is kept for the audit trail, and so are the bytes (§1, answer 8) — but nothing
        // reaches them any more.
        assertThat(Files.exists(bytes)).isTrue();

        mockMvc.perform(get("/api/documents/" + id + "/download").with(as(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/documents/" + id).with(as(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"gone\"}").with(as(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/count").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(admin)))
                .andExpect(jsonPath("$.count").value(0));

        assertThat(historyEntry("INVOICE", acmeInvoice.getId(), "DOCUMENT_DELETED")
                .get("changedByUserId").asLong()).isEqualTo(admin.getId());
    }

    @Test
    void whoeverUploadedItMayDeleteItEvenWithoutTheRecordsManagePrivilege() throws Exception {
        JsonNode mine = read(mockMvc.perform(uploadRequest(sales, "INVOICE", acmeInvoice.getId(),
                        part("mine.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated()));
        JsonNode theirs = upload(admin, "INVOICE", acmeInvoice.getId(), "theirs.pdf");

        // Sam keeps his documents when his role stops managing invoices; he still cannot touch
        // anyone else's.
        User sam = userRepository.findById(sales.getId()).orElseThrow();
        sam.setRole(documentKeeper());
        userRepository.save(sam);

        mockMvc.perform(uploadRequest(sales, "INVOICE", acmeInvoice.getId(),
                        part("another.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/documents/" + theirs.get("id").asLong()).with(as(sales)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/documents/" + mine.get("id").asLong()).with(as(sales)))
                .andExpect(status().isNoContent());
    }

    // ---- the record going away (AC-C5) -----------------------------------------

    @Test
    void deletingACustomerTakesItsDocumentsWithIt() throws Exception {
        Customer initech = customer("Initech");
        JsonNode onCustomer = upload(admin, "CUSTOMER", initech.getId(), "licence.pdf");
        JsonNode elsewhere = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");

        mockMvc.perform(delete("/api/customers/" + initech.getId()).with(as(admin)))
                .andExpect(status().isOk());

        assertThat(documentRepository.findById(onCustomer.get("id").asLong()).orElseThrow())
                .satisfies(d -> {
                    assertThat(d.isDeleted()).isTrue();
                    assertThat(d.getDeletedAt()).isNotNull();
                    assertThat(d.getDeletedByUserId()).isEqualTo(admin.getId());
                });
        mockMvc.perform(get("/api/documents/" + onCustomer.get("id").asLong() + "/download")
                        .with(as(admin)))
                .andExpect(status().isNotFound());

        // Another customer's documents are untouched.
        assertThat(documentRepository.findById(elsewhere.get("id").asLong()).orElseThrow().isDeleted())
                .isFalse();
    }

    // ---- storage that does not hold up (AC-C18) --------------------------------

    @Test
    void anUploadThatCannotBeStoredLeavesNoRow() throws Exception {
        // A file where the key's directory would go: storage cannot make it, so the write fails.
        Path blocked = root().resolve("invoice").resolve(String.valueOf(acmeInvoice.getId()));
        removeRecursively(blocked);
        Files.createDirectories(blocked.getParent());
        Files.writeString(blocked, "in the way");
        try {
            mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                            part("po.pdf", pdf(), "application/pdf")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("The file could not be stored"));

            assertThat(documentRepository.count()).isZero();
        } finally {
            Files.deleteIfExists(blocked);
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private Path root() {
        return Path.of(documentProperties.getLocal().getRoot()).toAbsolutePath().normalize();
    }

    private Path storedPath(long documentId) {
        return root().resolve(documentRepository.findById(documentId).orElseThrow().getStorageKey());
    }

    /** May attach nothing, and keeps what it attached before. */
    private Role documentKeeper() {
        return roleRepository.findByName("DOCUMENT_KEEPER").orElseGet(() ->
                roleRepository.save(Role.builder().name("DOCUMENT_KEEPER")
                        .description("Reads invoices, keeps its own documents")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName("INVOICE_VIEW").orElseThrow(),
                                privilegeRepository.findByName("SCOPE_OVERRIDE").orElseThrow(),
                                privilegeRepository.findByName("DOCUMENT_VIEW").orElseThrow(),
                                privilegeRepository.findByName("DOCUMENT_MANAGE").orElseThrow())))
                        .build()));
    }

    private JsonNode historyEntry(String entityType, Long entityId, String action) throws Exception {
        List<JsonNode> entries = historyEntries(entityType, entityId, action);
        assertThat(entries).as(action + " in the history").isNotEmpty();
        return entries.get(0);
    }

    /** The record's history rows for one action, newest first. */
    private List<JsonNode> historyEntries(String entityType, Long entityId, String action) throws Exception {
        JsonNode history = read(mockMvc.perform(get("/api/audit")
                        .param("entityType", entityType)
                        .param("entityId", String.valueOf(entityId))
                        .with(as(admin)))
                .andExpect(status().isOk()));
        return StreamSupport.stream(history.spliterator(), false)
                .filter(e -> action.equals(e.get("action").asText()))
                .toList();
    }

    /** Clears whatever an earlier run left at this path, file or directory. */
    private static void removeRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
