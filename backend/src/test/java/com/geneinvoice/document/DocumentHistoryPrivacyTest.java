package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The History tab is not a way round what a customer login may see (DASH-01).
 *
 * <p>A customer sees only SHARED documents on its own records (AC-C12), and never a member of
 * staff by name (AC-A8). The Documents tab held both lines; the timeline beside it did not. Its
 * snapshots carried the filename, the description, the visibility and the uploader's full name of
 * every internal file, because the only thing withheld was a field whose *name* said "poc".
 */
class DocumentHistoryPrivacyTest extends DocumentTestBase {

    private List<JsonNode> history(User caller, String entityType, Long entityId) throws Exception {
        String body = mockMvc.perform(get("/api/audit").with(as(caller))
                        .param("entityType", entityType)
                        .param("entityId", entityId.toString())
                        .param("includeRelated", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> rows = new ArrayList<>();
        objectMapper.readTree(body).forEach(rows::add);
        return rows;
    }

    private void share(long documentId) throws Exception {
        mockMvc.perform(patch("/api/documents/" + documentId).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"SHARED\"}"))
                .andExpect(status().isOk());
    }

    /** Uploads a file that is shared with the customer from the moment it lands. */
    private long uploadShared(User as, String entityType, Long entityId, String filename) throws Exception {
        return read(mockMvc.perform(uploadRequest(as, entityType, entityId,
                        part(filename, pdf(), "application/pdf"))
                        .param("visibility", "SHARED"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void describe(long documentId, String description) throws Exception {
        mockMvc.perform(patch("/api/documents/" + documentId).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("description", description))))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomerLoginIsNeverToldAboutAnInternalDocument() throws Exception {
        long internal = upload(admin, "CUSTOMER", acme.getId(), "internal-notes.pdf").get("id").asLong();
        describe(internal, "credit committee notes");
        uploadShared(admin, "CUSTOMER", acme.getId(), "statement.pdf");

        List<JsonNode> theirs = history(acmeLogin, "CUSTOMER", acme.getId());

        String all = theirs.toString();
        assertThat(all)
                .as("nothing of the internal file — not its name, its note, nor that it exists")
                .doesNotContain("internal-notes.pdf")
                .doesNotContain("credit committee notes")
                .doesNotContain("INTERNAL");
        assertThat(all).as("the file they can see is still in their history").contains("statement.pdf");
    }

    @Test
    void aDocumentDeletedWhileInternalIsNotMentionedEither() throws Exception {
        long internal = upload(admin, "CUSTOMER", acme.getId(), "secret-memo.pdf").get("id").asLong();
        mockMvc.perform(delete("/api/documents/" + internal).with(as(admin)))
                .andExpect(status().isNoContent());

        assertThat(history(acmeLogin, "CUSTOMER", acme.getId()).toString())
                .doesNotContain("secret-memo.pdf");
    }

    /** Sharing a file tells them about it from then on; what it was called while internal is not
     *  something the change entry may spell out either, since it names both ends of the change. */
    @Test
    void anInternalFileThatIsLaterSharedDoesNotLeakTheChangeItself() throws Exception {
        long doc = upload(admin, "CUSTOMER", acme.getId(), "was-internal.pdf").get("id").asLong();
        share(doc);

        List<JsonNode> theirs = history(acmeLogin, "CUSTOMER", acme.getId());

        assertThat(theirs.toString())
                .as("the entry recording the change has an INTERNAL side, so it is withheld whole")
                .doesNotContain("INTERNAL");
        // The document itself is theirs to see now, through the Documents tab.
        mockMvc.perform(get("/api/documents").with(as(acmeLogin))
                        .param("entityType", "CUSTOMER").param("entityId", acme.getId().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void noStaffMembersNameReachesACustomerThroughTheHistory() throws Exception {
        uploadShared(sales, "INVOICE", acmeInvoice.getId(), "delivery-note.pdf");
        upload(admin, "CUSTOMER", acme.getId(), "ledger.pdf");

        String all = history(acmeLogin, "CUSTOMER", acme.getId()).toString()
                + history(acmeLogin, "INVOICE", acmeInvoice.getId());

        // Whoever attached a file to the account is usually its POC; the snapshot named them in
        // full, which is the one identity the rest of the timeline is careful never to give up.
        assertThat(all)
                .doesNotContain(sales.getFullName())
                .doesNotContain(sales.getUsername())
                .doesNotContain(admin.getFullName())
                .doesNotContain(admin.getUsername());
    }

    /** Staff still see all of it — the rule is about who is asking, not about hiding history. */
    @Test
    void staffStillSeeTheWholeDocumentHistory() throws Exception {
        long internal = upload(admin, "CUSTOMER", acme.getId(), "internal-notes.pdf").get("id").asLong();
        describe(internal, "credit committee notes");

        assertThat(history(admin, "CUSTOMER", acme.getId()).toString())
                .contains("internal-notes.pdf")
                .contains("credit committee notes")
                .contains("INTERNAL");
    }
}
