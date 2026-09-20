package com.geneinvoice.document;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DOCUMENT_STORAGE=none}: there is nowhere to put bytes, so uploading says so and
 * everything else carries on (§4.4). A context of its own, on a database of its own, because the
 * storage is chosen when the context is built.
 */
@TestPropertySource(properties = {
        "app.documents.storage=none",
        // create-drop on the shared database would wipe the other test context's tables.
        "spring.datasource.url=jdbc:h2:mem:geneinvoice-documents-none-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000"
})
class DocumentStorageNoneTest extends DocumentTestBase {

    @Test
    void uploadingSaysThereIsNowhereToPutIt() throws Exception {
        mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Document storage is not configured"));

        assertThat(documentRepository.count()).isZero();
    }

    @Test
    void everythingElseKeepsWorking() throws Exception {
        mockMvc.perform(get("/api/documents").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/documents/count").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/invoices/" + acmeInvoice.getId()).with(as(admin)))
                .andExpect(status().isOk());
    }
}
