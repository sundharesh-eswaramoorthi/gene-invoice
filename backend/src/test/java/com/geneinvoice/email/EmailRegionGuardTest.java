package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sending company email about a record is a WRITE in that record's branch: EMAIL_SEND is
 * MANAGE-level in RegionRights, and the global authority on EmailController only says the caller
 * may send SOMEWHERE. B1 put regionAccess.requireManage on eighteen mutators and on neither the
 * send path nor the bulk send, so a caller holding MANAGE in one branch could mail another
 * branch's customer under the company's name on the strength of VIEW there — the one effect in
 * this application that cannot be taken back (B1).
 */
class EmailRegionGuardTest extends EmailTestBase {

    @Autowired CustomerService customerService;

    Region north;
    Region west;
    Customer northAccount;
    Customer westAccount;
    Invoice acmeInvoice;
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
        northAccount = opened("North Ltd", "north@north.test", "north.login", north);
        westAccount = opened("West Ltd", "west@west.test", "west.login", west);
        acmeInvoice = invoice(acme, sales);
        northInvoice = invoice(northAccount, northSales);
        // The seeded day-to-day role, unedited: CASHIER carries EMAIL_SEND, INVOICE_VIEW and
        // SCOPE_OVERRIDE, so the POC book contributes no predicate and only her grants stand
        // between her and another branch's records.
        vera = staffedIn("vera.visitor", "CASHIER",
                Map.of(defaultRegion().getId(), RegionRight.VIEW, north.getId(), RegionRight.MANAGE));
    }

    @Test
    void sendingAboutARecordInABranchYouCanOnlyReadIsRefused() throws Exception {
        // She can read the record and its email history. That half is R4's and is unchanged.
        getOk("/api/emails", vera, "entityType", "INVOICE", "entityId", acmeInvoice.getId().toString());

        // Mailing its customer under the company's name is a different question, and the answer
        // is 403 and not 404: she has just been shown the record (D-46).
        postJson("/api/emails", vera, email("INVOICE", acmeInvoice.getId(), List.of(toCustomer())))
                .andExpect(status().isForbidden());

        // The same person, the same privilege and the same endpoint in the branch she manages.
        assertThat(send(vera, email("INVOICE", northInvoice.getId(), List.of(toCustomer())))
                .get("id").asLong()).isPositive();

        assertThat(emailRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getEntityId()).isEqualTo(northInvoice.getId()));
    }

    @Test
    void aRecordInABranchYouHaveNothingInIsNotFoundRatherThanForbidden() throws Exception {
        postJson("/api/emails", vera, email("CUSTOMER", westAccount.getId(), List.of(toCustomer())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBulkSendMailsTheBranchesYouManageAndReportsTheRestRatherThanRefusingTheWholeClick()
            throws Exception {
        JsonNode result = read(postJson("/api/emails/bulk", vera, Map.of(
                "action", EmailService.BULK_ACTION,
                "selectAllMatchingFilter", true,
                "params", Map.of(
                        "entityType", "INVOICE",
                        "to", List.of(toCustomer()),
                        "subject", "About your invoice",
                        "body", "Hello")))
                .andExpect(status().isOk()));

        // One click over a filter that spans branches must not fan the hole out over every record
        // it can SEE: the guard is per record, so what she may send goes and what she may not is
        // named back to her.
        assertThat(plainIds(result.get("succeeded"))).containsExactly(northInvoice.getId());
        assertThat(outcomeIds(result.get("failed"))).containsExactly(acmeInvoice.getId());

        assertThat(emailRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getEntityId()).isEqualTo(northInvoice.getId()));
    }

    @Test
    void retryingAnEmailPutsTheSameMailBackOnTheWireAndIsAskedTheSameQuestion() throws Exception {
        JsonNode sent = send(admin, email("INVOICE", acmeInvoice.getId(), List.of(toCustomer())));

        mockMvc.perform(post("/api/emails/" + sent.get("id").asLong() + "/retry").with(as(vera)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRecordThatBelongsToNobodyHasNoBranchAndIsStillSendable() throws Exception {
        Product catalogue = product("Gizmo", "40.00");

        // PRODUCT, ROLE and an internal person are company-wide by declaration in RegionRights,
        // not by omission. A guard that read "no region means refuse" would have taken the
        // product and role mailers away from everybody but a wildcard holder.
        assertThat(send(vera, email("PRODUCT", catalogue.getId(), List.of(toUser(admin))))
                .get("id").asLong()).isPositive();
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** An account opened the way the application opens one, so it has its opening placement. */
    private Customer opened(String name, String customerEmail, String username, Region where) {
        actAs(admin);
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, customerEmail, null, null, where.getId(), username, "Password1!"));
    }

    /** Somebody staffed in exactly the branches named, at exactly the levels named, and nowhere else. */
    private User staffedIn(String username, String roleName, Map<Long, RegionRight> where) {
        User u = user(username, roleName);
        revokeRegionGrants(u);
        where.forEach((regionId, right) -> userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build()));
        return u;
    }

    /** succeeded is a list of ids; failed and skipped are outcomes that carry one plus a reason. */
    private List<Long> plainIds(JsonNode rows) {
        List<Long> out = new java.util.ArrayList<>();
        rows.forEach(row -> out.add(row.asLong()));
        return out;
    }

    private List<Long> outcomeIds(JsonNode rows) {
        List<Long> out = new java.util.ArrayList<>();
        rows.forEach(row -> out.add(row.get("id").asLong()));
        return out;
    }
}
