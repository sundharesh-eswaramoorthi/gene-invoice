package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.document.Document;
import com.geneinvoice.document.DocumentEntityType;
import com.geneinvoice.document.DocumentVisibility;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tabs on a record's page answer as of a date too (§5.7.17, D8): the POC tab from the seats as they were, the
 * documents and emails tabs from their own timestamps, and the history tab from the entries made by then.
 */
class AsOfTabsTest extends AsOfScenario {

    @Autowired private AuditLogRepository auditLogRepository;

    private List<String> ids(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    private JsonNode readAs(User caller, String url, LocalDate day) throws Exception {
        return body(call(get(url), caller, day).andExpect(status().isOk()));
    }

    @Test
    void thePocTabListsTheSeatsTheCustomerHadThen() throws Exception {
        on(D3);
        actAs(admin);
        Long betaSeat = pocService.add(beta.getId(), PocType.SUCCESS, sam.getId(), true).getId();
        on(TODAY);

        JsonNode d2 = read("/api/customers/" + acme.getId() + "/pocs", D2);
        assertThat(d2).hasSize(1);
        assertThat(d2.at("/0/id").asLong()).isEqualTo(seatId);
        assertThat(d2.at("/0/pocType").asText()).isEqualTo("SUCCESS");
        assertThat(d2.at("/0/primary").asBoolean()).isTrue();
        assertThat(d2.at("/0/user/username").asText()).isEqualTo("sam.sales");
        call(get("/api/customers/" + acme.getId() + "/pocs"), admin, D2)
                .andExpect(header().string("X-View-As-Of", D2.toString()));
        assertThat(read("/api/customers/" + acme.getId() + "/pocs", D3)).as("removed on D3").isEmpty();
        assertThat(read("/api/customers/" + acme.getId() + "/pocs", null)).isEmpty();

        assertThat(read("/api/customers/" + beta.getId() + "/pocs", D2)).as("added on D3").isEmpty();
        assertThat(ids(read("/api/customers/" + beta.getId() + "/pocs", D3))).containsExactly(betaSeat.toString());
        assertThat(ids(read("/api/customers/" + beta.getId() + "/pocs", null))).containsExactly(betaSeat.toString());

        call(get("/api/customers/" + gamma.getId() + "/pocs"), admin, D2).andExpect(status().isNotFound());
    }

    @Test
    void theDocumentsTabListsTheFilesTheRecordHadThen() throws Exception {
        on(D1);
        Document contract = document(acme, "contract.pdf", DocumentVisibility.INTERNAL);
        on(D3);
        Document renewal = document(acme, "renewal.pdf", DocumentVisibility.SHARED);
        on(D4);
        Document gone = documentRepository.findById(contract.getId()).orElseThrow();
        gone.setDeleted(true);
        gone.setDeletedAt(AppClock.now());
        gone.setDeletedByUserId(admin.getId());
        documentRepository.save(gone);
        on(TODAY);

        String list = "/api/documents?entityType=CUSTOMER&entityId=" + acme.getId();
        String count = "/api/documents/count?entityType=CUSTOMER&entityId=" + acme.getId();
        JsonNode d2 = read(list, D2);
        assertThat(ids(d2.get("content"))).containsExactly(contract.getId().toString());
        assertThat(d2.get("asOf").asText()).isEqualTo(D2.toString());
        assertThat(d2.at("/content/0/canEdit").asBoolean()).isFalse();
        assertThat(d2.at("/content/0/canDelete").asBoolean()).isFalse();
        assertThat(d2.at("/content/0/canDownload").asBoolean()).as("deleted since").isFalse();
        assertThat(read(count, D2).get("count").asLong()).isEqualTo(1);

        JsonNode d3 = read(list, D3);
        assertThat(ids(d3.get("content"))).containsExactly(renewal.getId().toString(), contract.getId().toString());
        assertThat(d3.at("/content/0/canDownload").asBoolean()).as("a read: opened, never changed").isTrue();
        assertThat(d3.get("totalElements").asLong()).isEqualTo(2);
        assertThat(read(count, D3).get("count").asLong()).isEqualTo(2);

        assertThat(ids(read(list, D4).get("content"))).containsExactly(renewal.getId().toString());
        assertThat(read(count, D4).get("count").asLong()).isEqualTo(1);
        call(get(list), admin, D0).andExpect(status().isNotFound());
        call(get(count), admin, D0).andExpect(status().isNotFound());

        JsonNode live = read(list, null);
        assertThat(ids(live.get("content"))).containsExactly(renewal.getId().toString());
        assertThat(live.at("/content/0/canEdit").asBoolean()).isTrue();
        assertThat(live.at("/content/0/canDelete").asBoolean()).isTrue();
        assertThat(live.at("/content/0/canDownload").asBoolean()).isTrue();
        assertThat(read(count, null).get("count").asLong()).isEqualTo(1);

        User acmeLogin = userRepository.findByUsername("acme.login").orElseThrow();
        assertThat(ids(readAs(acmeLogin, list, D3).get("content"))).as("only the shared file")
                .containsExactly(renewal.getId().toString());
        assertThat(readAs(acmeLogin, count, D3).get("count").asLong()).isEqualTo(1);
        assertThat(ids(readAs(acmeLogin, list, D2).get("content"))).isEmpty();

        call(get("/api/documents?entityType=CUSTOMER&entityId=" + gamma.getId()), admin, D2)
                .andExpect(status().isNotFound());
    }

    @Test
    void theEmailsTabListsTheEmailsWrittenByThen() throws Exception {
        on(D1);
        Email first = email(EmailEntityType.CUSTOMER, acme.getId(), "Customer Acme Ltd", EmailStatus.FAILED);
        Email aboutWidget = email(EmailEntityType.PRODUCT, widget.getId(), "Product Widget", EmailStatus.SENT);
        on(D3);
        Email second = email(EmailEntityType.CUSTOMER, acme.getId(), "Customer Zeta Holdings", EmailStatus.SENT);
        Email aboutWidgetPro = email(EmailEntityType.PRODUCT, widget.getId(), "Product Widget Pro", EmailStatus.SENT);
        on(TODAY);

        String list = "/api/emails?entityType=CUSTOMER&entityId=" + acme.getId();
        JsonNode d2 = read(list, D2);
        assertThat(ids(d2.get("content"))).containsExactly(first.getId().toString());
        assertThat(d2.get("totalElements").asLong()).isEqualTo(1);
        assertThat(d2.get("asOf").asText()).isEqualTo(D2.toString());
        assertThat(d2.at("/content/0/canRetry").asBoolean()).as("nothing is retried in the past").isFalse();
        assertThat(d2.at("/content/0/canOpenRecord").asBoolean()).as("every flag is off as of a date").isFalse();
        assertThat(d2.at("/content/0/status").asText()).isEqualTo("FAILED");

        JsonNode live = read(list, null);
        assertThat(ids(live.get("content"))).containsExactly(second.getId().toString(), first.getId().toString());
        assertThat(live.at("/content/1/canRetry").asBoolean()).isTrue();
        assertThat(live.at("/content/1/canOpenRecord").asBoolean()).isTrue();
        call(get(list), admin, D0).andExpect(status().isNotFound());

        assertThat(read("/api/emails/" + first.getId(), D2).get("canRetry").asBoolean()).isFalse();
        assertThat(read("/api/emails/" + first.getId(), D2).get("canOpenRecord").asBoolean()).isFalse();
        assertThat(read("/api/emails/" + first.getId(), null).get("canRetry").asBoolean()).isTrue();
        call(get("/api/emails/" + second.getId()), admin, D2).andExpect(status().isNotFound());
        assertThat(read("/api/emails/" + second.getId(), D3).get("id").asLong()).isEqualTo(second.getId());
        assertThat(read("/api/emails/" + second.getId(), null).get("id").asLong()).isEqualTo(second.getId());

        // The product email tab (CF-23): the product as it was, and only the emails up to then.
        String product = "/api/emails?entityType=PRODUCT&entityId=" + widget.getId();
        assertThat(ids(read(product, D2).get("content"))).containsExactly(aboutWidget.getId().toString());
        assertThat(ids(read(product, D3).get("content")))
                .containsExactly(aboutWidgetPro.getId().toString(), aboutWidget.getId().toString());
        call(get("/api/emails?entityType=PRODUCT&entityId=" + gadget.getId()), admin, D2)
                .andExpect(status().isNotFound());
        call(get("/api/emails?entityType=CUSTOMER&entityId=" + gamma.getId()), admin, D2)
                .andExpect(status().isNotFound());
    }

    @Test
    void theHistoryTabShowsTheEntriesMadeByThen() throws Exception {
        String customer = "/api/audit?entityType=CUSTOMER&entityId=" + acme.getId();
        Set<String> d2 = actions(read(customer, D2));
        Set<String> live = actions(read(customer, null));
        assertThat(d2).contains("CUSTOMER_CREATED", "POC_ASSIGNED")
                .doesNotContain("CUSTOMER_UPDATED", "CUSTOMER_REGION_CHANGED", "POC_REMOVED");
        assertThat(live).contains("CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_REGION_CHANGED", "POC_REMOVED");
        assertThat(createdAts(read(customer, D2))).allMatch(at -> !at.isAfter(endOf(D2)));

        String related = customer + "&includeRelated=true";
        Set<String> relatedD2 = actions(read(related, D2));
        assertThat(relatedD2).contains("INVOICE_CREATED", "PAYMENT_RECORDED", "PROMISE_CREATED")
                .doesNotContain("INVOICE_CANCELLED", "DISPUTE_OPENED", "DISPUTE_APPROVED", "CUSTOMER_UPDATED");
        assertThat(actions(read(related, null))).contains("INVOICE_CANCELLED", "DISPUTE_OPENED");
        assertThat(createdAts(read(related, D2))).allMatch(at -> !at.isAfter(endOf(D2)));

        String invoice = "/api/audit?entityType=INVOICE&entityId=" + y;
        assertThat(actions(read(invoice, D3))).contains("INVOICE_CREATED").doesNotContain("INVOICE_CANCELLED");
        assertThat(actions(read(invoice, null))).contains("INVOICE_CANCELLED");

        // A record with no version then has no history then; the deleted-record fallback is live only.
        call(get("/api/audit?entityType=CUSTOMER&entityId=" + gamma.getId()), admin, D2)
                .andExpect(status().isNotFound());
        read("/api/audit?entityType=CUSTOMER&entityId=" + gamma.getId(), null);
        call(get("/api/audit?entityType=PRODUCT&entityId=" + gadget.getId()), admin, D2)
                .andExpect(status().isNotFound());
        read("/api/audit?entityType=PRODUCT&entityId=" + gadget.getId(), D3);
        read("/api/audit?entityType=PRODUCT&entityId=" + widget.getId(), D2);
        on(D1);
        Customer delta = customer("Delta Co", north);
        on(D3);
        actAs(admin);
        customerService.delete(delta.getId());
        on(TODAY);
        String deleted = "/api/audit?entityType=CUSTOMER&entityId=" + delta.getId();
        assertThat(actions(read(deleted, D2))).doesNotContain("CUSTOMER_DELETED");
        call(get(deleted), admin, D4).andExpect(status().isNotFound());
        assertThat(actions(read(deleted, null))).contains("CUSTOMER_DELETED");
        User acmeLogin = userRepository.findByUsername("acme.login").orElseThrow();
        assertThat(actions(readAs(acmeLogin, customer, D2))).contains("CUSTOMER_CREATED")
                .doesNotContain("CUSTOMER_UPDATED");
    }

    @Test
    void anEntryDerivedFromTheRecordShowsItAsItWasThen() throws Exception {
        // Before auditing began: Acme has no CUSTOMER_CREATED row, so the trail derives one from the record.
        auditLogRepository.findByEntityTypeAndEntityIdIn("CUSTOMER", List.of(acme.getId())).stream()
                .filter(a -> a.getAction().equals("CUSTOMER_CREATED")).forEach(auditLogRepository::delete);
        String customer = "/api/audit?entityType=CUSTOMER&entityId=" + acme.getId();

        JsonNode then = created(read(customer, D2));
        assertThat(then.get("derived").asBoolean()).isTrue();
        assertThat(then.get("entityLabel").asText()).isEqualTo("Acme Ltd");
        assertThat(then.get("afterJson").asText()).contains("Acme Ltd").doesNotContain("Zeta");
        assertThat(created(read(customer + "&includeRelated=true", D2)).get("afterJson").asText())
                .contains("Acme Ltd");

        JsonNode now = created(read(customer, null));
        assertThat(now.get("entityLabel").asText()).isEqualTo("Zeta Holdings");
        assertThat(now.get("afterJson").asText()).contains("Zeta Holdings");
    }

    private static JsonNode created(JsonNode entries) {
        for (JsonNode e : entries) {
            if (e.get("action").asText().equals("CUSTOMER_CREATED")) return e;
        }
        throw new AssertionError("no CUSTOMER_CREATED in " + entries);
    }

    private static Set<String> actions(JsonNode entries) {
        Set<String> out = new TreeSet<>();
        entries.forEach(e -> out.add(e.get("action").asText()));
        return out;
    }

    private static List<Instant> createdAts(JsonNode entries) {
        List<Instant> out = new ArrayList<>();
        entries.forEach(e -> out.add(Instant.parse(e.get("createdAt").asText())));
        return out;
    }

    private static Instant endOf(LocalDate day) {
        return AsOfPoint.endOfDay(day).instant();
    }

    private Document document(Customer c, String filename, DocumentVisibility visibility) {
        return documentRepository.save(Document.builder()
                .entityType(DocumentEntityType.CUSTOMER).entityId(c.getId()).entityLabel("Customer " + c.getName())
                .customerId(c.getId()).filename(filename).contentType("application/pdf").sizeBytes(10)
                .checksum("0".repeat(64)).storageKey("customers/" + c.getId() + "/" + filename)
                .visibility(visibility).uploadedByUserId(admin.getId()).uploadedByName("Admin")
                .build());
    }

    private Email email(EmailEntityType type, Long entityId, String label, EmailStatus status) {
        return emailRepository.save(Email.builder()
                .entityType(type).entityId(entityId).entityLabel(label)
                .direction(EmailDirection.OUTBOUND).status(status).subject("About " + label)
                .fromUserId(admin.getId()).fromName("Admin").fromInternal(true).sentByUserId(admin.getId())
                .build());
    }
}
