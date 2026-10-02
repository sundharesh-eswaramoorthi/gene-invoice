package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who sees what as of a date (D9): today's grants, applied to each record's region and book as they were on the
 * day looked at, in every list, count and figure, and a customer login still sees only its own customer's records.
 */
class AsOfScopeTest extends AsOfScenario {

    private JsonNode page(String url, User caller, LocalDate day, String... params) throws Exception {
        var request = get(url);
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return body(call(request, caller, day).andExpect(status().isOk()));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("content").forEach(r -> out.add(r.get("id").asLong()));
        return out;
    }

    @Test
    void aSalesPocReassignedSinceStillSeesTheOldInvoicesAsTheyWere() throws Exception {
        User sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        grant(sid, north, AccessLevel.READ, RecordScope.BOOK);
        on(D3);
        actAs(admin);
        invoiceService.update(b, new InvoiceDtos.UpdateInvoiceRequest(null, sid.getId()));
        on(TODAY);

        String n = north.getId() + "," + south.getId();
        JsonNode d2 = page("/api/invoices", sam, D2, "regionIds", n);
        assertThat(ids(d2)).containsExactlyInAnyOrder(x, y, b);
        assertThat(d2.get("lockedFilters").toString()).isEqualTo("[\"salesPocUserId:eq:" + sam.getId() + "\"]");
        assertThat(ids(page("/api/invoices", sam, D3, "regionIds", n))).as("B went to Sid on D3")
                .containsExactlyInAnyOrder(x, y);
        assertThat(ids(page("/api/invoices", sam, null, "regionIds", n))).containsExactlyInAnyOrder(x, y);
        assertThat(ids(page("/api/invoices", sid, D2, "regionIds", north.getId().toString()))).isEmpty();
        assertThat(ids(page("/api/invoices", sid, null, "regionIds", north.getId().toString()))).containsExactly(b);

        call(get("/api/invoices/" + b), sam, D2).andExpect(status().isOk());
        call(get("/api/invoices/" + b), sam, null).andExpect(status().isNotFound());
        assertThat(page("/api/invoices/summary", sam, D2, "regionIds", n).get("count").asLong()).isEqualTo(3);
        assertThat(page("/api/invoices/summary", sam, null, "regionIds", n).get("count").asLong()).isEqualTo(2);

        // His customer book at T: Beta was his through invoice B.
        call(get("/api/customers/" + beta.getId()), sam, D2).andExpect(status().isOk());
        call(get("/api/customers/" + beta.getId()), sam, null).andExpect(status().isNotFound());

        JsonNode billed = page("/api/dashboard/billed-by-month", sam, D2, "months", "1", "regionIds", n);
        assertThat(billed.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(billed.at("/months/0/amount").decimalValue()).isEqualByComparingTo("1600.00");
        assertThat(page("/api/dashboard/billed-by-month", sam, null, "months", "1", "regionIds", n)
                .at("/months/0/amount").decimalValue()).as("Y cancelled, B gone to Sid").isEqualByComparingTo("1000.00");
    }

    @Test
    void aCustomerLoginSeesOnlyItsOwnRecordsAsTheyWere() throws Exception {
        User acmeLogin = userRepository.findByUsername("acme.login").orElseThrow();
        User betaLogin = customerUser("beta.login", beta.getId());

        assertThat(ids(page("/api/invoices", acmeLogin, D2))).containsExactlyInAnyOrder(x, y);
        assertThat(ids(page("/api/invoices", acmeLogin, D2, "regionIds", north.getId().toString())))
                .as("a customer login has no choice of region").containsExactlyInAnyOrder(x, y);
        assertThat(ids(page("/api/payments", acmeLogin, D2))).containsExactly(paymentId);
        assertThat(ids(page("/api/invoices", betaLogin, D2))).containsExactly(b);
        assertThat(ids(page("/api/payments", betaLogin, D2))).isEmpty();
        assertThat(ids(page("/api/customers", acmeLogin, D2))).containsExactly(acme.getId());
        assertThat(page("/api/customers", acmeLogin, D2).at("/content/0/name").asText()).isEqualTo("Acme Ltd");

        call(get("/api/invoices/" + b), acmeLogin, D2).andExpect(status().isNotFound());
        call(get("/api/customers/" + beta.getId()), acmeLogin, D2).andExpect(status().isForbidden());
        call(get("/api/payments/" + paymentId), betaLogin, D2).andExpect(status().isNotFound());

        JsonNode own = page("/api/dashboard/outstanding-by-age", acmeLogin, D2);
        assertThat(own.get("coverage").asText()).isEqualTo("OWN");
        assertThat(own.at("/buckets/0/amount").decimalValue()).isEqualByComparingTo("1100.00");
        assertThat(page("/api/dashboard/outstanding-by-age", betaLogin, D2).at("/buckets/0/amount").decimalValue())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void someoneWhoReadsOnlyNorthSeesAcmeWhileItWasInNorth() throws Exception {
        User nora = user("nora.north", "ADMIN");
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.READ, RecordScope.ALL);

        call(get("/api/customers/" + acme.getId()), nora, D2).andExpect(status().isOk());
        call(get("/api/customers/" + acme.getId()), nora, D4).andExpect(status().isNotFound());
        call(get("/api/customers/" + acme.getId()), nora, null).andExpect(status().isNotFound());

        JsonNode d2 = page("/api/customers", nora, D2);
        assertThat(ids(d2)).containsExactlyInAnyOrder(acme.getId(), beta.getId());
        assertThat(d2.get("regionIds").toString()).isEqualTo("[" + north.getId() + "]");
        assertThat(ids(page("/api/customers", nora, D4))).containsExactlyInAnyOrder(beta.getId(), gamma.getId());
        assertThat(ids(page("/api/invoices", nora, D2))).containsExactlyInAnyOrder(x, y, b);
        assertThat(ids(page("/api/invoices", nora, D4))).containsExactly(b);
        assertThat(ids(page("/api/invoices", nora, null))).containsExactly(b);

        // The tabs of a record follow its region then too.
        call(get("/api/customers/" + acme.getId() + "/pocs"), nora, D2).andExpect(status().isOk());
        call(get("/api/customers/" + acme.getId() + "/pocs"), nora, D4).andExpect(status().isNotFound());
        call(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", acme.getId().toString()),
                nora, D2).andExpect(status().isOk());
        call(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", acme.getId().toString()),
                nora, D4).andExpect(status().isNotFound());

        JsonNode top = page("/api/dashboard/top-outstanding-customers", nora, D2);
        assertThat(top.at("/customers/0/customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(page("/api/dashboard/top-outstanding-customers", nora, D4).get("customers")).hasSize(1);

        // An admin asking about NORTH alone gets the same answer.
        String n = north.getId().toString();
        assertThat(ids(page("/api/customers", admin, D2, "regionIds", n)))
                .containsExactlyInAnyOrder(acme.getId(), beta.getId());
        assertThat(ids(page("/api/customers", admin, D4, "regionIds", n)))
                .containsExactlyInAnyOrder(beta.getId(), gamma.getId());
        assertThat(ids(page("/api/customers", admin, D4, "regionIds", south.getId().toString())))
                .containsExactly(acme.getId());
    }
}
