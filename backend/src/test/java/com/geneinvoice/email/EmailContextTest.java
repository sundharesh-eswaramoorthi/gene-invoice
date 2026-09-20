package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the compose form is given before sending: roles, addresses, suggestions, people and a preview. */
class EmailContextTest extends EmailTestBase {

    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;

    private JsonNode context(User caller, String... params) throws Exception {
        return getOk("/api/emails/context", caller, params);
    }

    /** A suggested token as it is written down: {@code ROLE:CUSTOMER:COLLECTION_POC}, {@code USER:7}. */
    private List<String> tokens(JsonNode suggestion) {
        return java.util.stream.StreamSupport.stream(suggestion.get("to").spliterator(), false)
                .map(t -> t.get("type").asText() + (t.has("level") ? ":" + t.get("level").asText() : "")
                        + (t.has("role") ? ":" + t.get("role").asText() : "")
                        + (t.has("userId") ? ":" + t.get("userId").asLong() : ""))
                .toList();
    }

    // ---- context -------------------------------------------------------------------

    @Test
    void aRecordsContextNamesWhoHoldsEachRoleAndTheCustomersAddresses() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        JsonNode ctx = context(admin, "entityType", "INVOICE", "entityId", inv.getId().toString());

        assertThat(ctx.get("entityLabel").asText()).isEqualTo("Invoice " + inv.getInvoiceNumber());
        assertThat(ctx.get("entityLink").asText()).isEqualTo("/invoices/" + inv.getId());
        assertThat(ctx.at("/delivery/configured").asBoolean()).isFalse();
        assertThat(ctx.get("delivery").has("mailbox")).isFalse();
        assertThat(ctx.at("/sender/restricted").asBoolean()).isFalse();
        assertThat(ctx.at("/sender/self/userId").asLong()).isEqualTo(admin.getId());
        assertThat(ctx.at("/sender/self/email").asText()).isEqualTo("admin@geneinvoice.local");

        // Both groups, customer level first, each role in the same order (L4). The customer level
        // offers the two seats a customer can hold: the Sales POC is the invoice's own (CP-01).
        assertThat(ctx.get("roles")).extracting(r -> r.get("level").asText() + ":" + r.get("role").asText())
                .containsExactly("CUSTOMER:CUSTOMER_SUCCESS_POC", "CUSTOMER:COLLECTION_POC",
                        "RECORD:SALES_POC");
        assertThat(ctx.get("roles")).extracting(r -> r.get("groupLabel").asText())
                .containsExactly("Customer level", "Customer level", "Invoice level");
        assertThat(ctx.at("/roles/0/label").asText()).isEqualTo("Customer Success POC");
        assertThat(ctx.at("/roles/0/levelLabel").asText()).isEqualTo("Customer");
        assertThat(ctx.at("/roles/0/resolved").asBoolean()).isFalse();
        assertThat(ctx.at("/roles/0/people")).isEmpty();
        assertThat(ctx.at("/roles/0/sender").isNull()).isTrue();
        assertThat(ctx.at("/roles/1/people/0/userId").asLong()).isEqualTo(collections.getId());
        assertThat(ctx.at("/roles/1/sender/userId").asLong()).isEqualTo(collections.getId());
        // The invoice's own Sales POC, under the record's own heading — the only Sales POC there is.
        assertThat(ctx.at("/roles/2/levelLabel").asText()).isEqualTo("Invoice");
        assertThat(ctx.at("/roles/2/resolved").asBoolean()).isTrue();
        assertThat(ctx.at("/roles/2/people")).hasSize(1);
        assertThat(ctx.at("/roles/2/people/0/name").asText()).isEqualTo("SAM.SALES");
        assertThat(ctx.at("/roles/2/people/0/email").asText()).isEqualTo("sam.sales@test.local");
        assertThat(ctx.at("/roles/2/sender/userId").asLong()).isEqualTo(sales.getId());

        assertThat(ctx.at("/customerEmails/available").asBoolean()).isTrue();
        assertThat(ctx.at("/customerEmails/addresses")).extracting(a -> a.get("address").asText())
                .containsExactly("ap@acme.test", "acme.login@test.local");
        assertThat(ctx.get("suggestion").isNull()).isTrue();
    }

    @Test
    void aSeatRoleListsEveryActiveHolderItReachesAndTheOneWhoWouldSend() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);
        String invoiceId = inv.getId().toString();

        JsonNode collection = context(admin, "entityType", "INVOICE", "entityId", invoiceId).at("/roles/1");
        assertThat(collection.get("role").asText()).isEqualTo("COLLECTION_POC");
        assertThat(collection.get("resolved").asBoolean()).isTrue();
        assertThat(collection.get("people")).extracting(p -> p.get("userId").asLong())
                .containsExactly(collections.getId(), cora.getId());
        assertThat(collection.at("/people/1/name").asText()).isEqualTo("CORA.COLLECTIONS");
        assertThat(collection.at("/people/1/email").asText()).isEqualTo("cora.collections@test.local");
        assertThat(collection.at("/sender/userId").asLong()).isEqualTo(collections.getId());
        assertThat(collection.has("person")).isFalse();

        // With the primary gone, the next active holder is everyone the role reaches, and sends.
        deactivate(collections);
        JsonNode after = context(admin, "entityType", "INVOICE", "entityId", invoiceId).at("/roles/1");
        assertThat(after.get("people")).extracting(p -> p.get("userId").asLong()).containsExactly(cora.getId());
        assertThat(after.at("/sender/userId").asLong()).isEqualTo(cora.getId());

        // A customer login learns the role is filled, not by whom.
        JsonNode masked = context(acmeLogin, "entityType", "INVOICE", "entityId", invoiceId);
        assertThat(masked.at("/roles/1/resolved").asBoolean()).isTrue();
        assertThat(masked.at("/roles/1/people")).isEmpty();
        assertThat(masked.at("/roles/1/sender").isNull()).isTrue();
        assertThat(masked.toString()).doesNotContain("cora.collections", "CORA.COLLECTIONS", "SAM.SALES");
    }

    @Test
    void theCustomersBookIsReadOnceForAllItsSeats() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.SUCCESS, success);
        seat(acme, PocType.COLLECTION, collections);

        long reads = CountingStatements.reads("customer_pocs",
                () -> context(admin, "entityType", "INVOICE", "entityId", inv.getId().toString()));

        // Every customer-level role comes out of one read of the book (L2). Once per role is
        // twice the queries on every record of a bulk send, for the same answer.
        assertThat(reads).isEqualTo(1);
    }

    /**
     * Every role the compose form offers is one somebody can actually hold (CP-01).
     *
     * <p>The customer level used to offer a Sales POC seat that neither write path would create —
     * {@code PocService.add} and the bulk Add-POC both refuse a customer-level SALES outright — so
     * the entry could never resolve. It was permanently unusable as a recipient, disabled Send
     * when picked as the sender, and skipped every row of a bulk send.
     */
    @Test
    void everyRoleTheComposeFormOffersIsOneSomebodyCanActuallyHold() throws Exception {
        Invoice inv = invoice(acme, sales);
        String invoiceId = inv.getId().toString();

        for (JsonNode role : context(admin, "entityType", "INVOICE", "entityId", invoiceId).get("roles")) {
            if (!"CUSTOMER".equals(role.get("level").asText())) continue;
            PocType seat = EmailRole.valueOf(role.get("role").asText()).pocType();
            mockMvc.perform(post("/api/customers/" + acme.getId() + "/pocs").with(as(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("pocType", seat.name(),
                                    "userId", assignableAs(seat).getId(), "primary", true))))
                    .andExpect(status().isOk());
        }

        // With a holder in every seat the form names, nothing it offers is left unheld.
        JsonNode filled = context(admin, "entityType", "INVOICE", "entityId", invoiceId);
        assertThat(filled.get("roles")).isNotEmpty();
        assertThat(filled.get("roles")).allSatisfy(r -> {
            assertThat(r.get("resolved").asBoolean())
                    .as("%s is offered and can be held", r.get("label").asText()).isTrue();
            assertThat(r.get("people")).isNotEmpty();
        });
    }

    /** Somebody this deployment can seat as that kind of POC. */
    private User assignableAs(PocType type) {
        return switch (type) {
            case SUCCESS -> success;
            case COLLECTION -> collections;
            case SALES -> sales;
        };
    }

    @Test
    void withoutARecordTheContextDescribesTheKind() throws Exception {
        JsonNode invoices = context(admin, "entityType", "INVOICE");
        assertThat(invoices.get("entityId").isNull()).isTrue();
        assertThat(invoices.get("entityLabel").isNull()).isTrue();
        assertThat(invoices.at("/roles/0/resolved").isNull()).isTrue();
        assertThat(invoices.get("roles")).allSatisfy(r -> {
            assertThat(r.get("people")).isEmpty();
            assertThat(r.get("sender").isNull()).isTrue();
        });
        assertThat(invoices.at("/customerEmails/available").asBoolean()).isTrue();
        assertThat(invoices.at("/customerEmails/addresses")).isEmpty();

        JsonNode products = context(admin, "entityType", "PRODUCT", "event", "CREATED");
        assertThat(products.get("roles")).isEmpty();
        assertThat(products.at("/customerEmails/available").asBoolean()).isFalse();
        assertThat(products.get("suggestion").isNull()).isTrue();

        // An internal user has no customer, so no customer emails apply to them.
        JsonNode internalUser = context(admin, "entityType", "USER", "entityId", sales.getId().toString());
        assertThat(internalUser.at("/customerEmails/available").asBoolean()).isFalse();
    }

    @Test
    void onlyACustomerLoginsRecordOffersItsCustomersRoles() throws Exception {
        seat(acme, PocType.COLLECTION, collections);

        // Nobody could ever hold a seat on a user who belongs to no customer.
        assertThat(context(admin, "entityType", "USER", "entityId", sales.getId().toString()).get("roles")).isEmpty();

        JsonNode login = context(admin, "entityType", "USER", "entityId", acmeLogin.getId().toString());
        // A user stores no POC of its own, so only the customer's book applies.
        assertThat(login.get("roles")).extracting(r -> r.get("level").asText() + ":" + r.get("role").asText())
                .containsExactly("CUSTOMER:CUSTOMER_SUCCESS_POC", "CUSTOMER:COLLECTION_POC");
        assertThat(login.at("/roles/1/people/0/userId").asLong()).isEqualTo(collections.getId());
        assertThat(login.at("/customerEmails/available").asBoolean()).isTrue();

        // A list or bulk compose may cover customer logins, so the kind still offers them.
        assertThat(context(admin, "entityType", "USER").get("roles")).extracting(r -> r.get("role").asText())
                .containsExactly("CUSTOMER_SUCCESS_POC", "COLLECTION_POC");
    }

    @Test
    void aCustomerLoginIsToldARoleIsFilledButNotByWhom() throws Exception {
        Invoice inv = invoice(acme, sales);

        JsonNode ctx = context(acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString(),
                "event", "CREATED");

        assertThat(ctx.at("/sender/restricted").asBoolean()).isTrue();
        assertThat(ctx.at("/sender/self/userId").asLong()).isEqualTo(acmeLogin.getId());
        // They see the groups and which roles are filled, and no names in either (E13).
        assertThat(ctx.at("/roles/2/groupLabel").asText()).isEqualTo("Invoice level");
        assertThat(ctx.at("/roles/2/resolved").asBoolean()).isTrue();
        assertThat(ctx.at("/roles/2/people")).isEmpty();
        assertThat(ctx.at("/roles/2/sender").isNull()).isTrue();
        assertThat(ctx.at("/customerEmails/addresses")).hasSize(2);
        assertThat(tokens(ctx.get("suggestion"))).containsExactly("CUSTOMER");

        mockMvc.perform(get("/api/emails/context").with(as(acmeLogin))
                        .param("entityType", "PRODUCT").param("entityId", widget.getId().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/emails/context").with(as(acmeLogin)).param("entityType", "USER"))
                .andExpect(status().isForbidden());
    }

    /**
     * Both groups are offered wherever they exist, held or not (L4): a dispute on a payment offers
     * the Sales POC its target cannot have, and says nobody is assigned to it.
     */
    @Test
    void bothGroupsAreOfferedWhetherOrNotAnybodyHoldsThem() throws Exception {
        User cole = user("cole.collections", "COLLECTION_POC");
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, cole.getId(), null));
        actAs(acmeLogin);
        Dispute onPayment = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, payment.getId(), "Not ours", null));
        actAs(admin);

        JsonNode ctx = context(admin, "entityType", "DISPUTE", "entityId", onPayment.getId().toString());

        assertThat(ctx.get("roles")).extracting(r -> r.get("level").asText() + ":" + r.get("role").asText())
                .containsExactly("CUSTOMER:CUSTOMER_SUCCESS_POC", "CUSTOMER:COLLECTION_POC",
                        "RECORD:SALES_POC", "RECORD:COLLECTION_POC");
        assertThat(ctx.get("roles")).extracting(r -> r.get("groupLabel").asText())
                .containsExactly("Customer level", "Customer level", "Dispute level", "Dispute level");
        assertThat(ctx.get("roles")).extracting(r -> r.get("resolved").asBoolean())
                .containsExactly(false, false, false, true);
        assertThat(ctx.at("/roles/3/people/0/userId").asLong()).isEqualTo(cole.getId());
        assertThat(ctx.at("/roles/3/sender/userId").asLong()).isEqualTo(cole.getId());

        // Before any record is picked, a kind offers the same two groups and says nothing of who.
        JsonNode kind = context(admin, "entityType", "DISPUTE");
        assertThat(kind.get("roles")).extracting(r -> r.get("level").asText() + ":" + r.get("role").asText())
                .containsExactly("CUSTOMER:CUSTOMER_SUCCESS_POC", "CUSTOMER:COLLECTION_POC",
                        "RECORD:SALES_POC", "RECORD:COLLECTION_POC");
        assertThat(kind.get("roles")).allSatisfy(r -> {
            assertThat(r.get("resolved").isNull()).isTrue();
            assertThat(r.get("people")).isEmpty();
        });
    }

    @Test
    void theContextRespectsTheCallersBookAndPrivileges() throws Exception {
        Invoice theirs = invoice(acme, otherSales);

        mockMvc.perform(get("/api/emails/context").with(as(sales))
                        .param("entityType", "INVOICE").param("entityId", theirs.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emails/context").with(as(user("vic.viewer", "VIEWER")))
                        .param("entityType", "INVOICE"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/emails/context").with(as(admin))
                        .param("entityType", "INVOICE").param("event", "DELETED"))
                .andExpect(status().isBadRequest());
    }

    // ---- suggestions -----------------------------------------------------------------

    @Test
    void eachKindOfRecordSuggestsAnEmailForItsEvents() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode invoiceCreated = context(admin, "entityType", "INVOICE", "entityId", inv.getId().toString(),
                "event", "CREATED").get("suggestion");
        assertThat(invoiceCreated.get("subject").asText())
                .isEqualTo("Invoice " + inv.getInvoiceNumber() + " for ₹1,200.00");
        assertThat(invoiceCreated.get("body").asText()).contains("Balance due: ₹1,200.00");
        assertThat(tokens(invoiceCreated)).containsExactly("CUSTOMER");

        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("150000.50"), "Bank transfer", null, null, collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.of(2026, 10, 1), collections.getId(), null, null));
        actAs(acmeLogin);
        Dispute dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));
        actAs(admin);

        JsonNode paymentCreated = context(admin, "entityType", "PAYMENT", "entityId", payment.getId().toString(),
                "event", "CREATED").get("suggestion");
        assertThat(paymentCreated.get("subject").asText()).isEqualTo("Payment of ₹1,50,000.50 received");
        assertThat(paymentCreated.get("body").asText()).contains("Method: Bank transfer");

        JsonNode promiseUpdated = context(admin, "entityType", "PROMISE", "entityId", promise.id().toString(),
                "event", "UPDATED").get("suggestion");
        assertThat(promiseUpdated.get("subject").asText()).isEqualTo("Payment promise updated: ₹500.00 by 2026-10-01");
        assertThat(promiseUpdated.get("body").asText()).contains("Status: Open");
        assertThat(tokens(promiseUpdated)).containsExactly("CUSTOMER",
                "ROLE:CUSTOMER:COLLECTION_POC", "ROLE:RECORD:COLLECTION_POC");
        assertThat(context(admin, "entityType", "PROMISE", "entityId", promise.id().toString(),
                "event", "CREATED").at("/suggestion/subject").asText())
                .isEqualTo("Payment promise: ₹500.00 by 2026-10-01");

        JsonNode disputeCreated = context(admin, "entityType", "DISPUTE", "entityId", dispute.getId().toString(),
                "event", "CREATED").get("suggestion");
        assertThat(disputeCreated.get("subject").asText())
                .isEqualTo("Dispute #" + dispute.getId() + " raised on Invoice " + inv.getInvoiceNumber());
        assertThat(disputeCreated.get("body").asText()).contains("Wrong quantity");
        // The customer's people, and the Sales POC its invoice target names (§3).
        assertThat(tokens(disputeCreated)).containsExactly("ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC",
                "ROLE:CUSTOMER:COLLECTION_POC", "ROLE:RECORD:SALES_POC");

        actAs(admin); // a MockMvc request clears the thread's security context behind it
        disputeService.deny(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("Quantity was right", null));
        JsonNode disputeUpdated = context(admin, "entityType", "DISPUTE", "entityId", dispute.getId().toString(),
                "event", "UPDATED").get("suggestion");
        assertThat(disputeUpdated.get("subject").asText()).isEqualTo("Dispute #" + dispute.getId() + " denied");
        assertThat(disputeUpdated.get("body").asText()).contains("Quantity was right");
        assertThat(tokens(disputeUpdated)).containsExactly("CUSTOMER");

        assertThat(context(admin, "entityType", "CUSTOMER", "entityId", acme.getId().toString(),
                "event", "CREATED").at("/suggestion/subject").asText()).isEqualTo("Welcome, Acme Ltd");
        assertThat(context(admin, "entityType", "CUSTOMER", "entityId", acme.getId().toString(),
                "event", "UPDATED").get("suggestion").isNull()).isTrue();
        JsonNode productCreated = context(admin, "entityType", "PRODUCT", "entityId", widget.getId().toString(),
                "event", "CREATED").get("suggestion");
        assertThat(productCreated.get("subject").asText()).isEqualTo("New product: Widget");
        assertThat(productCreated.get("to")).isEmpty();
        assertThat(context(admin, "entityType", "ROLE", "entityId", role("VIEWER").getId().toString(),
                "event", "CREATED").at("/suggestion/subject").asText()).isEqualTo("New role: VIEWER");

        JsonNode staffAccount = context(admin, "entityType", "USER", "entityId", sales.getId().toString(),
                "event", "CREATED").get("suggestion");
        assertThat(staffAccount.get("subject").asText()).isEqualTo("Your Gene Invoice account");
        assertThat(staffAccount.get("body").asText()).contains("Username: sam.sales");
        assertThat(tokens(staffAccount)).containsExactly("USER:" + sales.getId());
        assertThat(tokens(context(admin, "entityType", "USER", "entityId", acmeLogin.getId().toString(),
                "event", "CREATED").get("suggestion"))).containsExactly("CUSTOMER");
    }

    @Test
    void suggestedDatesAreTheDayWhereTheReaderIs() throws Exception {
        // 23:52 UTC is 05:22 the next morning in India, and that is the day the app shows there.
        Instant lateUtc = Instant.parse("2026-09-16T23:52:00Z");
        Invoice inv = invoice(acme, sales);
        Invoice stored = invoiceRepository.findById(inv.getId()).orElseThrow();
        stored.setInvoiceDate(lateUtc);
        invoiceRepository.save(stored);
        actAs(admin);
        var payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, null, collections.getId(), null));
        Payment paid = paymentRepository.findById(payment.getId()).orElseThrow();
        paid.setPaidAt(lateUtc);
        paymentRepository.save(paid);

        String invoiceId = inv.getId().toString();
        assertThat(context(admin, "entityType", "INVOICE", "entityId", invoiceId, "event", "CREATED",
                "utcOffsetMinutes", "330").at("/suggestion/body").asText()).contains("Date: 2026-09-17");
        assertThat(context(admin, "entityType", "PAYMENT", "entityId", payment.getId().toString(), "event", "CREATED",
                "utcOffsetMinutes", "330").at("/suggestion/body").asText()).contains("Date: 2026-09-17");
        assertThat(context(admin, "entityType", "INVOICE", "entityId", invoiceId, "event", "CREATED",
                "utcOffsetMinutes", "-300").at("/suggestion/body").asText()).contains("Date: 2026-09-16");
        assertThat(context(admin, "entityType", "INVOICE", "entityId", invoiceId, "event", "CREATED",
                "utcOffsetMinutes", "840").at("/suggestion/body").asText()).contains("Date: 2026-09-17");
        // Without the reader's offset, the server's own time zone.
        assertThat(context(admin, "entityType", "INVOICE", "entityId", invoiceId, "event", "CREATED")
                .at("/suggestion/body").asText())
                .contains("Date: " + LocalDate.ofInstant(lateUtc, ZoneId.systemDefault()));

        for (String outOfRange : List.of("841", "-841")) {
            mockMvc.perform(get("/api/emails/context").with(as(admin)).param("entityType", "INVOICE")
                            .param("entityId", invoiceId).param("event", "CREATED").param("utcOffsetMinutes", outOfRange))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("utcOffsetMinutes must be between -840 and 840"));
        }
        mockMvc.perform(get("/api/emails/context").with(as(admin)).param("entityType", "INVOICE")
                        .param("utcOffsetMinutes", "IST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moneyReadsTheWayTheAppShowsIt() {
        assertThat(EmailText.money(new BigDecimal("1200"))).isEqualTo("₹1,200.00");
        assertThat(EmailText.money(new BigDecimal("999.5"))).isEqualTo("₹999.50");
        assertThat(EmailText.money(new BigDecimal("120000"))).isEqualTo("₹1,20,000.00");
        assertThat(EmailText.money(new BigDecimal("12345678.905"))).isEqualTo("₹1,23,45,678.91");
        assertThat(EmailText.money(new BigDecimal("-50"))).isEqualTo("-₹50.00");
    }

    // ---- people and preview ------------------------------------------------------------

    @Test
    void peopleSearchFindsActiveStaffByAnyNameOrAddress() throws Exception {
        User cora = user("cora.collections", "COLLECTION_POC");
        deactivate(cora);

        JsonNode found = getOk("/api/emails/people", admin, "q", "COLLECTIONS");
        assertThat(found).extracting(p -> p.get("username").asText()).containsExactly("cara.collections");
        assertThat(found.at("/0/email").asText()).isEqualTo("cara.collections@test.local");

        assertThat(getOk("/api/emails/people", admin, "q", "@test.local"))
                .extracting(p -> p.get("username").asText())
                .contains("sam.sales", "sid.sales", "sue.success")
                .doesNotContain("acme.login", "cora.collections");
        assertThat(getOk("/api/emails/people", admin)).hasSizeLessThanOrEqualTo(20);

        mockMvc.perform(get("/api/emails/people").with(as(acmeLogin)).param("q", "sam"))
                .andExpect(status().isForbidden());
    }

    @Test
    void peopleSearchMatchesWildcardCharactersLiterally() throws Exception {
        user("pat_under", "VIEWER");
        user("per%cent", "VIEWER");
        user("back\\slash", "VIEWER");

        assertThat(getOk("/api/emails/people", admin, "q", "%"))
                .extracting(p -> p.get("username").asText()).containsExactly("per%cent");
        assertThat(getOk("/api/emails/people", admin, "q", "_"))
                .extracting(p -> p.get("username").asText()).containsExactly("pat_under");
        assertThat(getOk("/api/emails/people", admin, "q", "\\"))
                .extracting(p -> p.get("username").asText()).containsExactly("back\\slash");
        assertThat(getOk("/api/emails/people", admin, "q", "t_u"))
                .extracting(p -> p.get("username").asText()).containsExactly("pat_under");
        assertThat(getOk("/api/emails/people", admin, "q", "m_s")).isEmpty();
    }

    @Test
    void aPreviewShowsWhoWouldReceiveItAndWhatStopsItWithoutSavingAnything() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);

        JsonNode preview = read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(),
                List.of(toUser(collections), toRole("COLLECTION_POC"), toRole("CUSTOMER_SUCCESS_POC"))))
                .andExpect(status().isOk()));
        assertThat(preview.at("/from/userId").asLong()).isEqualTo(admin.getId());
        assertThat(preview.get("to")).hasSize(1);
        assertThat(preview.at("/to/0/sources")).extracting(s -> s.get("type").asText()).containsExactly("USER", "ROLE");
        assertThat(preview.at("/unresolved/0/token").asText()).isEqualTo("ROLE:CUSTOMER:CUSTOMER_SUCCESS_POC");
        assertThat(preview.get("problems")).isEmpty();

        deactivate(sales);
        JsonNode stuck = read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(), List.of(),
                "from", toRole("SALES_POC", "RECORD"), "subject", "")).andExpect(status().isOk()));
        assertThat(stuck.get("from").isNull()).isTrue();
        assertThat(stuck.get("problems")).extracting(JsonNode::asText).containsExactly(
                "Nobody holds Sales POC (this invoice) on Invoice " + inv.getInvoiceNumber()
                        + ", so it cannot be the sender",
                "Add at least one recipient");

        JsonNode masked = read(postJson("/api/emails/preview", acmeLogin, email("INVOICE", inv.getId(),
                List.of(toRole("COLLECTION_POC"), toCustomer()))).andExpect(status().isOk()));
        assertThat(masked.at("/to/0/name").asText()).isEqualTo("Collection POC (customer)");
        assertThat(masked.at("/to/0/address").isNull()).isTrue();
        assertThat(masked.at("/from/name").asText()).isEqualTo("ACME.LOGIN");

        postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(), List.of(toRole("OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "role must be one of [SALES_POC, CUSTOMER_SUCCESS_POC, COLLECTION_POC]"));
        assertThat(emailRepository.count()).isZero();
    }

    // ---- who can send from their Gmail -------------------------------------------------

    private void mirror(User u, ConnectionStatus status) {
        gmailConnectionRepository.save(GmailConnection.builder().userId(u.getId()).status(status)
                .gmailAddress(u.getUsername() + "@gmail.com").build());
    }

    @Test
    void everyPersonTheComposeFormShowsSaysWhetherTheyCanSendFromTheirGmail() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);
        mirror(admin, ConnectionStatus.CONNECTED);
        mirror(collections, ConnectionStatus.NEEDS_RECONNECT);
        mirror(cora, ConnectionStatus.DISCONNECTED);

        JsonNode ctx = context(admin, "entityType", "INVOICE", "entityId", inv.getId().toString());
        assertThat(ctx.at("/sender/self/gmail").asText()).isEqualTo("CONNECTED");
        assertThat(ctx.at("/roles/2/people/0/gmail").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(ctx.at("/roles/1/people")).extracting(p -> p.get("gmail").asText())
                .containsExactly("NEEDS_RECONNECT", "NOT_CONNECTED");
        assertThat(ctx.at("/roles/1/sender/gmail").asText()).isEqualTo("NEEDS_RECONNECT");

        JsonNode found = getOk("/api/emails/people", admin, "q", "collections");
        assertThat(found).extracting(p -> p.get("username").asText() + ":" + p.get("gmail").asText())
                .contains("cara.collections:NEEDS_RECONNECT", "cora.collections:NOT_CONNECTED");

        // Customer logins do not connect Gmail; whom a role stands for they are not told at all.
        JsonNode theirs = context(acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString());
        assertThat(theirs.at("/sender/self/gmail").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(theirs.at("/roles/1/people")).isEmpty();
        assertThat(theirs.at("/roles/1/sender").isNull()).isTrue();
    }

    @Test
    void aPreviewWarnsWhenTheEmailWouldBeSavedButNotSent() throws Exception {
        Invoice inv = invoice(acme, sales);
        Map<String, Object> fromMe = email("INVOICE", inv.getId(), List.of(toCustomer()));

        JsonNode off = read(postJson("/api/emails/preview", admin, fromMe).andExpect(status().isOk()));
        assertThat(off.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "Email delivery is not configured, so this email will be saved in the app but not sent.");

        mailTransport.mode(RecordingMailTransport.Mode.SUCCESS);
        JsonNode notConnected = read(postJson("/api/emails/preview", admin, fromMe).andExpect(status().isOk()));
        assertThat(notConnected.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "System Administrator has not connected Gmail, so this email will be saved in the app but not sent.");

        mirror(sales, ConnectionStatus.NEEDS_RECONNECT);
        JsonNode renew = read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(),
                List.of(toCustomer()), "from", toRole("SALES_POC", "RECORD"))).andExpect(status().isOk()));
        assertThat(renew.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "SAM.SALES's Gmail connection needs to be renewed, so this email will be saved in the app but not sent.");

        mirror(admin, ConnectionStatus.CONNECTED);
        JsonNode fine = read(postJson("/api/emails/preview", admin, fromMe).andExpect(status().isOk()));
        assertThat(fine.get("warnings")).isEmpty();
        // Warnings do not stop Send; problems do.
        assertThat(fine.get("problems")).isEmpty();

        JsonNode customer = read(postJson("/api/emails/preview", acmeLogin, fromMe).andExpect(status().isOk()));
        assertThat(customer.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "Email from a customer login is saved in the app and is not sent through Gmail.");

        // A sender role nobody holds is a problem, not a warning.
        deactivate(sales);
        JsonNode nobody = read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(),
                List.of(toCustomer()), "from", toRole("SALES_POC", "RECORD"))).andExpect(status().isOk()));
        assertThat(nobody.get("warnings")).isEmpty();
        assertThat(nobody.get("problems")).hasSize(1);
    }
}
