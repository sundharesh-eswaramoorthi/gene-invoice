package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentAccessTest extends DocumentTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    @Test
    void readingNeedsDocumentViewAndChangingNeedsDocumentManage() throws Exception {
        JsonNode onInvoice = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = onInvoice.get("id").asLong();

        mockMvc.perform(get("/api/documents").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(viewer)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + id + "/download").with(as(viewer)))
                .andExpect(status().isOk());
        mockMvc.perform(uploadRequest(viewer, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"no\"}").with(as(viewer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/documents/" + id).with(as(viewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRoleWithoutDocumentViewSeesNothingOnARecordItCanOtherwiseRead() throws Exception {
        JsonNode onInvoice = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        User reader = user("ivy.reader", invoicesOnly().getName());

        mockMvc.perform(get("/api/invoices/" + acmeInvoice.getId()).with(as(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(reader)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/documents/" + onInvoice.get("id").asLong() + "/download")
                        .with(as(reader)))
                .andExpect(status().isForbidden());
    }

    @Test
    void attachingAlsoNeedsTheRecordsOwnManagePrivilege() throws Exception {
        Payment payment = payment(acme, "500.00");

        mockMvc.perform(uploadRequest(sales, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated());
        mockMvc.perform(uploadRequest(sales, "CUSTOMER", acme.getId(),
                        part("licence.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());
        mockMvc.perform(uploadRequest(sales, "PAYMENT", payment.getId(),
                        part("cheque.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPocCannotReachADocumentOnARecordOutsideTheirBook() throws Exception {
        JsonNode acmeDoc = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = acmeDoc.get("id").asLong();

        mockMvc.perform(get("/api/documents/" + id + "/download").with(as(otherSales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"mine now\"}").with(as(otherSales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/documents/" + id).with(as(otherSales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(otherSales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/count").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(otherSales)))
                .andExpect(status().isNotFound());
        mockMvc.perform(uploadRequest(otherSales, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isNotFound());
        assertThat(documentRepository.findById(id).orElseThrow().isDeleted()).isFalse();

        mockMvc.perform(uploadRequest(otherSales, "INVOICE", globexInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated());
    }

    @Test
    void aCustomerLoginCannotReachAnotherCustomersDocument() throws Exception {
        JsonNode acmeDoc = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = acmeDoc.get("id").asLong();
        share(id);

        // Shared, and still not theirs. Downloading resolves the document's invoice, which now
        // answers a customer login for another customer's record exactly as it answers one for a
        // record that does not exist — otherwise the difference is an oracle for which ids exist
        // (AUTH-08).
        mockMvc.perform(get("/api/documents/" + id + "/download").with(as(globexLogin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(globexLogin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents/count").param("entityType", "CUSTOMER")
                        .param("entityId", String.valueOf(acme.getId())).with(as(globexLogin)))
                .andExpect(status().isForbidden());
        // Uploading resolves the same invoice, so it answers the same way: a record this login
        // cannot see is one that is not there, whether or not it exists (AUTH-08).
        mockMvc.perform(uploadRequest(globexLogin, "INVOICE", acmeInvoice.getId(),
                        part("po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerLoginSeesOnlySharedDocumentsOnItsOwnRecords() throws Exception {
        JsonNode internal = upload(admin, "INVOICE", acmeInvoice.getId(), "internal-notes.pdf");
        JsonNode shared = upload(admin, "INVOICE", acmeInvoice.getId(), "signed-po.pdf");
        share(shared.get("id").asLong());

        JsonNode listed = read(mockMvc.perform(get("/api/documents")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .with(as(acmeLogin)))
                .andExpect(status().isOk()));
        assertThat(listed.get("totalElements").asInt()).isEqualTo(1);
        assertThat(listed.at("/content/0/filename").asText()).isEqualTo("signed-po.pdf");

        mockMvc.perform(get("/api/documents/count").param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId())).with(as(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/documents/" + shared.get("id").asLong() + "/download").with(as(acmeLogin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + internal.get("id").asLong() + "/download").with(as(acmeLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerLoginUploadsOnItsOwnRecordsAndTheUploadIsShared() throws Exception {
        JsonNode dto = read(mockMvc.perform(uploadRequest(acmeLogin, "INVOICE", acmeInvoice.getId(),
                        part("our-signed-po.pdf", pdf(), "application/pdf"))
                        .param("visibility", "INTERNAL"))
                .andExpect(status().isCreated()));

        assertThat(dto.get("visibility").asText()).isEqualTo("SHARED");
        assertThat(dto.at("/uploadedBy/userId").asLong()).isEqualTo(acmeLogin.getId());
        assertThat(dto.get("canDownload").asBoolean()).isTrue();
        assertThat(dto.get("canEdit").asBoolean()).isFalse();
        assertThat(dto.get("canDelete").asBoolean()).isFalse();

        Document stored = documentRepository.findById(dto.get("id").asLong()).orElseThrow();
        assertThat(stored.getVisibility()).isEqualTo(DocumentVisibility.SHARED);
        assertThat(stored.getCustomerId()).isEqualTo(acme.getId());
    }

    @Test
    void aCustomerLoginMayNotEditOrDeleteEvenItsOwnUpload() throws Exception {
        JsonNode mine = read(mockMvc.perform(uploadRequest(acmeLogin, "INVOICE", acmeInvoice.getId(),
                        part("our-signed-po.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated()));
        long id = mine.get("id").asLong();

        mockMvc.perform(patch("/api/documents/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"INTERNAL\"}").with(as(acmeLogin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/documents/" + id).with(as(acmeLogin)))
                .andExpect(status().isForbidden());
        assertThat(documentRepository.findById(id).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void aCustomerLoginMayUploadToItsOwnCustomerAndPaymentRecordsToo() throws Exception {
        Payment payment = payment(acme, "500.00");

        mockMvc.perform(uploadRequest(acmeLogin, "CUSTOMER", acme.getId(),
                        part("licence.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated());
        mockMvc.perform(uploadRequest(acmeLogin, "PAYMENT", payment.getId(),
                        part("bank-advice.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated());
    }

    @Test
    void theDtoSaysWhatThisCallerMayDoWithThisDocument() throws Exception {
        JsonNode asAdmin = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        assertThat(asAdmin.get("canEdit").asBoolean()).isTrue();
        assertThat(asAdmin.get("canDelete").asBoolean()).isTrue();

        JsonNode forViewer = firstListed(viewer);
        assertThat(forViewer.get("canDownload").asBoolean()).isTrue();
        assertThat(forViewer.get("canEdit").asBoolean()).isFalse();
        assertThat(forViewer.get("canDelete").asBoolean()).isFalse();

        JsonNode forSales = firstListed(sales);
        assertThat(forSales.get("id").asLong()).isEqualTo(asAdmin.get("id").asLong());
        assertThat(forSales.get("canEdit").asBoolean()).isTrue();
        assertThat(forSales.get("canDelete").asBoolean()).isTrue();
    }

    private Role invoicesOnly() {
        return roleRepository.findByName("INVOICE_READER_NO_DOCUMENTS").orElseGet(() ->
                roleRepository.save(Role.builder().name("INVOICE_READER_NO_DOCUMENTS")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName("INVOICE_VIEW").orElseThrow(),
                                privilegeRepository.findByName("SCOPE_OVERRIDE").orElseThrow())))
                        .build()));
    }

    private void share(long documentId) throws Exception {
        mockMvc.perform(patch("/api/documents/" + documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"SHARED\"}")
                        .with(as(admin)))
                .andExpect(status().isOk());
    }

    private JsonNode firstListed(User who) throws Exception {
        return read(mockMvc.perform(get("/api/documents")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .with(as(who)))
                .andExpect(status().isOk()))
                .at("/content/0");
    }
}
