package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attaching, publishing and removing a document are WRITES, and DOCUMENT_MANAGE is a MANAGE-level
 * privilege in RegionRights. B1 put regionAccess.requireManage on eighteen mutators and on none of
 * these, so the per-record question the document paths asked was the global "may I manage
 * documents somewhere?" plus a VIEW-level read of the record — which is the plain negation of
 * B1's central invariant, VIEW &lt; MANAGE per branch. The hole was reachable from the seeded
 * CASHIER role, and its effects are destructive (another branch's document soft-deleted) and
 * confidentiality-breaking (that branch's INTERNAL document flipped to SHARED, into its customer's
 * own portal) (B1).
 */
class DocumentRegionGuardTest extends DocumentTestBase {

    @Autowired CustomerService customerService;

    Region north;
    Region west;
    Customer northAccount;
    Customer westAccount;
    Invoice northInvoice;
    User northSales;
    /** May READ the branch Acme is in and MANAGE the north one, and holds nothing at all in WEST. */
    User vera;

    @BeforeEach
    void regionFixtures() {
        actAs(admin);
        north = region("NORTH");
        west = region("WEST");
        northSales = staffedIn("nan.north", DataSeeder.ROLE_SALES_POC,
                Map.of(north.getId(), RegionRight.MANAGE));
        northAccount = opened("North Ltd", "north.login", north);
        westAccount = opened("West Ltd", "west.login", west);
        northInvoice = invoice(northAccount, northSales);
        // The seeded day-to-day role, unedited: CASHIER carries DOCUMENT_MANAGE, INVOICE_MANAGE
        // and SCOPE_OVERRIDE, so the POC book contributes no predicate and only her grants stand
        // between her and another branch's records.
        vera = staffedIn("vera.visitor", "CASHIER",
                Map.of(defaultRegion().getId(), RegionRight.VIEW, north.getId(), RegionRight.MANAGE));
    }

    @Test
    void uploadingToARecordInABranchYouCanOnlyReadIsRefused() throws Exception {
        // She can see it. That half is R4's and is unchanged.
        mockMvc.perform(get("/api/documents?entityType=INVOICE&entityId=" + acmeInvoice.getId())
                        .with(as(vera)))
                .andExpect(status().isOk());

        // Attaching a file to it is a different question with a different answer, and the answer
        // is 403 and not 404: she has just been shown the record (D-46).
        mockMvc.perform(uploadRequest(vera, "INVOICE", acmeInvoice.getId(),
                        part("contract.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());
        mockMvc.perform(uploadRequest(vera, "CUSTOMER", acme.getId(),
                        part("contract.pdf", pdf(), "application/pdf")))
                .andExpect(status().isForbidden());

        // The same person, the same privilege and the same endpoint in the branch she manages.
        mockMvc.perform(uploadRequest(vera, "INVOICE", northInvoice.getId(),
                        part("contract.pdf", pdf(), "application/pdf")))
                .andExpect(status().isCreated());

        assertThat(documentRepository.findAll()).hasSize(1);
    }

    @Test
    void publishingAnotherBranchesInternalDocumentToItsCustomerIsRefused() throws Exception {
        JsonNode internal = upload(admin, "INVOICE", acmeInvoice.getId(), "internal.pdf");

        // PATCH {"visibility":"SHARED"} is the one document write that changes who can READ the
        // file, so it is the one that matters most: it publishes an HQ document into the HQ
        // customer's self-service portal.
        mockMvc.perform(patch("/api/documents/" + internal.get("id").asLong()).with(as(vera))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"SHARED\"}"))
                .andExpect(status().isForbidden());

        assertThat(documentRepository.findById(internal.get("id").asLong()).orElseThrow()
                .getVisibility()).isEqualTo(DocumentVisibility.INTERNAL);
    }

    @Test
    void deletingADocumentInABranchYouCanOnlyReadIsRefusedEvenWhenYouUploadedIt() throws Exception {
        JsonNode theirs = upload(admin, "INVOICE", acmeInvoice.getId(), "theirs.pdf");

        mockMvc.perform(delete("/api/documents/" + theirs.get("id").asLong()).with(as(vera)))
                .andExpect(status().isForbidden());

        // And the uploader rule does not buy her past it. It says WHOSE documents you may remove,
        // never WHERE: somebody moved from MANAGE to VIEW in a branch keeps neither.
        grant(vera, defaultRegion(), RegionRight.MANAGE);
        JsonNode hers = upload(vera, "INVOICE", acmeInvoice.getId(), "hers.pdf");
        revoke(vera, defaultRegion());
        grant(vera, defaultRegion(), RegionRight.VIEW);

        mockMvc.perform(delete("/api/documents/" + hers.get("id").asLong()).with(as(vera)))
                .andExpect(status().isForbidden());

        assertThat(documentRepository.findById(hers.get("id").asLong()).orElseThrow().isDeleted())
                .isFalse();

        // The same person and the same endpoint in the branch she manages.
        JsonNode mine = upload(vera, "INVOICE", northInvoice.getId(), "mine.pdf");
        mockMvc.perform(delete("/api/documents/" + mine.get("id").asLong()).with(as(vera)))
                .andExpect(status().isNoContent());
    }

    @Test
    void aRecordInABranchYouHaveNothingInIsNotFoundRatherThanForbidden() throws Exception {
        // Nothing is being told to her about WEST, because nothing there is hers to be told
        // about: the read gate answers first and the answer is "no such record" (AUTH-08).
        mockMvc.perform(uploadRequest(vera, "CUSTOMER", westAccount.getId(),
                        part("contract.pdf", pdf(), "application/pdf")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRowCarriesBackTheDeleteButtonItsOwnBranchWillHonour() throws Exception {
        upload(admin, "INVOICE", acmeInvoice.getId(), "theirs.pdf");
        upload(admin, "INVOICE", northInvoice.getId(), "ours.pdf");

        // The flag is per record and not per session. Offering Delete on a row the server is
        // about to refuse is how a user learns their permissions from an error dialog.
        mockMvc.perform(get("/api/documents?entityType=INVOICE&entityId=" + acmeInvoice.getId())
                        .with(as(vera)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].canEdit").value(false))
                .andExpect(jsonPath("$.content[0].canDelete").value(false));

        mockMvc.perform(get("/api/documents?entityType=INVOICE&entityId=" + northInvoice.getId())
                        .with(as(vera)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].canEdit").value(true));
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** An account opened the way the application opens one, so it has its opening placement. */
    private Customer opened(String name, String username, Region where) {
        actAs(admin);
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, where.getId(), username, "Password1!"));
    }

    /** Somebody staffed in exactly the branches named, at exactly the levels named, and nowhere else. */
    private User staffedIn(String username, String roleName, Map<Long, RegionRight> where) {
        User u = user(username, roleName);
        revokeRegionGrants(u);
        where.forEach((regionId, right) -> userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build()));
        return u;
    }

    private void grant(User u, Region where, RegionRight right) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId()).right(right).build());
    }

    private void revoke(User u, Region where) {
        userRegionGrantRepository.findByUserId(u.getId()).stream()
                .filter(g -> where.getId().equals(g.getRegionId()))
                .forEach(userRegionGrantRepository::delete);
    }
}
