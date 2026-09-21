package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.RecordingMailTransport.Outcome;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read which email, and what a customer login sees of staff (E13): only emails their
 * customer took part in, with staff as the role they hold or as the team.
 */
class EmailAccessTest extends EmailTestBase {

    @Autowired PrivilegeRepository privilegeRepository;

    @Test
    void aCustomerLoginMayNotAddressStaffByName() throws Exception {
        Invoice inv = invoice(acme, sales);

        postJson("/api/emails", acmeLogin, email("INVOICE", inv.getId(), List.of(toUser(sales))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerLoginSendsAsThemselvesOnly() throws Exception {
        Invoice inv = invoice(acme, sales);

        postJson("/api/emails", acmeLogin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "RECORD")),
                        "from", toUser(admin)))
                .andExpect(status().isForbidden());
        postJson("/api/emails", acmeLogin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "RECORD")),
                        "from", toRole("SALES_POC", "RECORD")))
                .andExpect(status().isForbidden());

        JsonNode sent = send(acmeLogin, email("INVOICE", inv.getId(),
                List.of(toRole("SALES_POC", "RECORD"), toCustomer()), "from", toUser(acmeLogin)));

        Email stored = emailRepository.findById(sent.get("id").asLong()).orElseThrow();
        assertThat(stored.getFromUserId()).isEqualTo(acmeLogin.getId());
        assertThat(stored.getFromCustomerId()).isEqualTo(acme.getId());
        assertThat(stored.isFromInternal()).isFalse();
        assertThat(sent.at("/to/0/masked").asBoolean()).isTrue();
        assertThat(sent.at("/to/0/name").asText()).isEqualTo("Sales POC (this invoice)");
        assertThat(sent.at("/to/0/address").isNull()).isTrue();
        assertThat(sent.at("/to/0/userId").isNull()).isTrue();
        assertThat(getOk("/api/inbox/unread-count", sales).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void aCustomerLoginWritingToARoleReachesEveryHolderButSeesOnlyTheRole() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);

        JsonNode preview = read(postJson("/api/emails/preview", acmeLogin, email("INVOICE", inv.getId(),
                List.of(toRole("COLLECTION_POC")))).andExpect(status().isOk()));
        JsonNode sent = send(acmeLogin, email("INVOICE", inv.getId(), List.of(toRole("COLLECTION_POC"), toCustomer())));

        assertThat(recipientsOf(sent.get("id").asLong())).filteredOn(EmailRecipient::isInternal)
                .extracting(EmailRecipient::getUserId).containsExactly(collections.getId(), cora.getId());
        assertThat(mailTransport.submissions()).isEmpty();
        assertThat(getOk("/api/inbox/unread-count", collections).get("count").asLong()).isEqualTo(1);
        assertThat(getOk("/api/inbox/unread-count", cora).get("count").asLong()).isEqualTo(1);

        JsonNode listed = getOk("/api/emails", acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .at("/content/0");
        for (JsonNode shown : List.of(preview, sent, listed)) {
            assertThat(shown.get("to")).filteredOn(p -> p.get("internal").asBoolean()).singleElement().satisfies(p -> {
                assertThat(p.get("masked").asBoolean()).isTrue();
                assertThat(p.get("name").asText()).isEqualTo("Collection POC (customer)");
                assertThat(p.get("address").isNull()).isTrue();
                assertThat(p.get("userId").isNull()).isTrue();
                assertThat(p.get("sources")).singleElement().satisfies(s -> {
                    assertThat(s.get("role").asText()).isEqualTo("COLLECTION_POC");
                    assertThat(s.get("level").asText()).isEqualTo("CUSTOMER");
                });
            });
            assertThat(shown.toString())
                    .doesNotContain("cara.collections", "cora.collections", "CARA.COLLECTIONS", "CORA.COLLECTIONS");
        }
        assertThat(getOk("/api/emails/" + sent.get("id").asLong(), admin).get("to"))
                .filteredOn(p -> p.get("internal").asBoolean()).extracting(p -> p.get("userId").asLong())
                .containsExactly(collections.getId(), cora.getId());
    }

    @Test
    void aCustomerSeesEachRoleAndTheTeamOnceHoweverManyStaffAreBehindThem() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);

        long id = send(admin, email("INVOICE", inv.getId(), List.of(toUser(sales), toRole("COLLECTION_POC"),
                toUser(success), toUser(cora), toCustomer()))).get("id").asLong();

        assertThat(getOk("/api/emails/" + id, admin).get("to")).extracting(p -> p.get("name").asText())
                .containsExactly("SAM.SALES", "CARA.COLLECTIONS", "CORA.COLLECTIONS", "SUE.SUCCESS",
                        "Acme Ltd", "ACME.LOGIN");

        JsonNode opened = getOk("/api/emails/" + id, acmeLogin);
        JsonNode listed = getOk("/api/emails", acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .at("/content/0");
        for (JsonNode shown : List.of(opened, listed)) {
            JsonNode to = shown.get("to");
            assertThat(to).extracting(p -> p.get("name").asText())
                    .containsExactly("Gene Invoice team", "Collection POC (customer)", "Acme Ltd", "ACME.LOGIN");
            assertThat(to.at("/0/masked").asBoolean()).isTrue();
            assertThat(to.at("/0/sources")).extracting(s -> s.get("type").asText()).containsExactly("USER");
            assertThat(to.at("/1/sources")).extracting(s -> s.get("type").asText()).containsExactly("ROLE", "USER");
            assertThat(to.at("/1/userId").isNull()).isTrue();
            assertThat(to.at("/2/masked").asBoolean()).isFalse();
            assertThat(to.at("/3/userId").asLong()).isEqualTo(acmeLogin.getId());
            assertThat(shown.toString()).doesNotContain("sam.sales", "sue.success", "cara.collections", "cora.collections",
                    "SAM.SALES", "SUE.SUCCESS", "CARA.COLLECTIONS", "CORA.COLLECTIONS");
        }
    }

    @Test
    void aStaffRecipientWhoseStoredRoleCannotBeReadIsTheTeam() throws Exception {
        Invoice inv = invoice(acme, sales);
        Email stored = emailRepository.save(Email.builder()
                .entityType(EmailEntityType.INVOICE)
                .entityId(inv.getId())
                .entityLabel("Invoice " + inv.getInvoiceNumber())
                .direction(EmailDirection.OUTBOUND)
                .status(EmailStatus.SENT)
                .subject("Written by another version")
                .fromUserId(admin.getId())
                .fromName("ADMIN")
                .fromAddress("admin@test.local")
                .fromInternal(true)
                .sentByUserId(admin.getId())
                .build());
        emailRecipientRepository.save(EmailRecipient.builder()
                .email(stored).field(RecipientField.TO).userId(collections.getId())
                .name("CARA.COLLECTIONS").address("cara.collections@test.local").internal(true)
                .sources("ROLE:CUSTOMER:ESCALATION_POC").build());
        emailRecipientRepository.save(EmailRecipient.builder()
                .email(stored).field(RecipientField.TO).userId(acmeLogin.getId())
                .customerId(acme.getId()).name("ACME.LOGIN").address("acme.login@test.local")
                .sources("CUSTOMER").build());

        JsonNode shown = getOk("/api/emails/" + stored.getId(), acmeLogin);

        assertThat(shown.get("to")).extracting(p -> p.get("name").asText())
                .containsExactly("Gene Invoice team", "ACME.LOGIN");
        assertThat(shown.at("/to/0/masked").asBoolean()).isTrue();
        assertThat(shown.at("/to/0/sources/0/label").isMissingNode()).isTrue();
        assertThat(shown.toString()).doesNotContain("cara.collections", "CARA.COLLECTIONS");
    }

    @Test
    void aCustomerSeesHowTheirOwnPeopleGotAnEmailButNothingOfHowStaffDid() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> copy.address().equals("sam.sales@test.local")
                ? new Outcome(RecipientDeliveryStatus.FAILED, "Gmail refused sam.sales@test.local (400)")
                : new Outcome(RecipientDeliveryStatus.SENT, null));
        Invoice inv = invoice(acme, sales);
        long id = send(admin, email("INVOICE", inv.getId(),
                List.of(toCustomer(), toRole("SALES_POC", "RECORD")))).get("id").asLong();
        EmailRecipient login = recipientsOf(id).get(1);
        login.setRead(true);
        login.setReadAt(java.time.Instant.parse("2026-09-20T11:00:00Z"));
        emailRecipientRepository.save(login);

        JsonNode staffView = getOk("/api/emails/" + id, admin);
        assertThat(staffView.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(staffView.get("error").asText()).isEqualTo("Gmail refused sam.sales@test.local (400)");
        assertThat(staffView.at("/to/2/delivery/status").asText()).isEqualTo("FAILED");
        assertThat(staffView.at("/to/2/delivery/error").asText()).isEqualTo("Gmail refused sam.sales@test.local (400)");

        JsonNode customerView = getOk("/api/emails/" + id, acmeLogin);
        assertThat(customerView.get("to")).extracting(p -> p.get("name").asText())
                .containsExactly("Acme Ltd", "ACME.LOGIN", "Sales POC (this invoice)");
        assertThat(customerView.at("/to/0/delivery/status").asText()).isEqualTo("SENT");
        assertThat(customerView.at("/to/1/delivery/readInAppAt").asText()).isEqualTo("2026-09-20T11:00:00Z");
        assertThat(customerView.at("/to/2/masked").asBoolean()).isTrue();
        assertThat(customerView.at("/to/2/delivery").isNull()).isTrue();
        assertThat(customerView.get("status").asText()).isEqualTo("SENT");
        assertThat(customerView.get("error").isNull()).isTrue();
        assertThat(getOk("/api/emails", acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .at("/content/0/status").asText()).isEqualTo("SENT");
        assertThat(getOk("/api/inbox", acmeLogin).at("/content/0/status").asText()).isEqualTo("SENT");
        assertThat(getOk("/api/inbox", sales).at("/content/0/status").asText()).isEqualTo("PARTIAL");
        assertThat(customerView.toString()).doesNotContain("sam.sales");
    }

    @Test
    void aCustomerLoginRetriesOnlyWhatTheySent() throws Exception {
        Invoice inv = invoice(acme, sales);
        long staffs = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        JsonNode own = send(acmeLogin, email("INVOICE", inv.getId(),
                List.of(toRole("SALES_POC", "RECORD"), toCustomer())));
        mailTransport.mode(Mode.SUCCESS);

        assertThat(getOk("/api/emails/" + staffs, acmeLogin).get("canRetry").asBoolean()).isFalse();
        assertThat(getOk("/api/emails", acmeLogin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .findValues("canRetry")).extracting(JsonNode::asBoolean).containsExactlyInAnyOrder(true, false);
        mockMvc.perform(post("/api/emails/" + staffs + "/retry").with(as(acmeLogin))).andExpect(status().isForbidden());
        assertThat(mailTransport.submissions()).isEmpty();

        assertThat(own.get("canRetry").asBoolean()).isTrue();
        JsonNode retried = read(mockMvc.perform(post("/api/emails/" + own.get("id").asLong() + "/retry").with(as(acmeLogin)))
                .andExpect(status().isOk()));
        assertThat(retried.get("error").asText()).isEqualTo("Email from a customer login is not sent through Gmail");
        assertThat(mailTransport.submissions()).isEmpty();
        assertThat(getOk("/api/emails/" + staffs, admin).get("canRetry").asBoolean()).isTrue();
    }

    @Test
    void aCustomerIsNotShownHowStaffEmailWasDeliveredOrWhyItFailed() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        Invoice inv = invoice(acme, sales);
        long staffs = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        long own = send(acmeLogin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        for (long id : List.of(staffs, own)) {
            Email email = emailRepository.findById(id).orElseThrow();
            email.setDeliveredFrom("sender" + id + "@gmail.com");
            emailRepository.save(email);
        }

        assertThat(getOk("/api/emails/" + staffs, acmeLogin).get("deliveredFrom").isNull()).isTrue();
        assertThat(getOk("/api/emails/" + staffs, admin).get("deliveredFrom").asText()).isEqualTo("sender" + staffs + "@gmail.com");
        assertThat(getOk("/api/emails/" + own, acmeLogin).get("deliveredFrom").asText()).isEqualTo("sender" + own + "@gmail.com");
        assertThat(getOk("/api/emails/" + own, acmeLogin).get("error").asText())
                .isEqualTo("Email from a customer login is not sent through Gmail");

        mailTransport.mode(Mode.PERMANENT_FAILURE);
        long failed = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        assertThat(getOk("/api/emails/" + failed, acmeLogin).get("error").asText()).isEqualTo("Could not be delivered");
        assertThat(getOk("/api/emails/" + failed, acmeLogin).at("/to/0/delivery/error").asText())
                .isEqualTo("Could not be delivered");
        assertThat(getOk("/api/emails/" + failed, admin).get("error").asText()).isEqualTo(RecordingMailTransport.REFUSED);
        mailTransport.mode(Mode.SUCCESS);
        mailTransport.outcome(copy -> Outcome.notSent("SYSTEM ADMINISTRATOR has not connected Gmail"));
        long unconnected = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        assertThat(getOk("/api/emails/" + unconnected, acmeLogin).get("error").asText()).isEqualTo("Could not be delivered");
        assertThat(getOk("/api/emails/" + unconnected, admin).get("error").asText())
                .isEqualTo("SYSTEM ADMINISTRATOR has not connected Gmail");
        mailTransport.mode(Mode.NOT_CONFIGURED);
        long unsent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()))).get("id").asLong();
        assertThat(getOk("/api/emails/" + unsent, acmeLogin).get("error").asText())
                .isEqualTo("Email delivery is not configured (mail service)");
    }

    @Test
    void anEmailBetweenStaffOnlyIsInvisibleToTheCustomer() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode internal = send(admin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "RECORD"))));
        JsonNode toCustomer = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));

        JsonNode page = getOk("/api/emails", acmeLogin,
                "entityType", "INVOICE", "entityId", inv.getId().toString());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.at("/content/0/id").asLong()).isEqualTo(toCustomer.get("id").asLong());

        mockMvc.perform(get("/api/emails/" + internal.get("id").asLong()).with(as(acmeLogin)))
                .andExpect(status().isNotFound());
        assertThat(getOk("/api/emails", admin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void aCustomerSeesStaffAsTheirRoleOrTheTeamWithNoNameOrAddress() throws Exception {
        Invoice inv = invoice(acme, sales);
        send(admin, email("INVOICE", inv.getId(), List.of(toRole("SALES_POC", "RECORD"), toCustomer())));

        JsonNode shown = getOk("/api/emails", acmeLogin,
                "entityType", "INVOICE", "entityId", inv.getId().toString()).at("/content/0");
        assertThat(shown.at("/from/masked").asBoolean()).isTrue();
        assertThat(shown.at("/from/name").asText()).isEqualTo("Gene Invoice team");
        assertThat(shown.at("/from/address").isNull()).isTrue();
        assertThat(shown.at("/from/userId").isNull()).isTrue();
        assertThat(shown.at("/sentBy/userId").isNull()).isTrue();
        assertThat(shown.at("/sentBy/name").asText()).isEqualTo("Gene Invoice team");
        assertThat(shown.at("/to/0/name").asText()).isEqualTo("Sales POC (this invoice)");
        assertThat(shown.at("/to/0/address").isNull()).isTrue();
        assertThat(shown.at("/to/1/address").asText()).isEqualTo("ap@acme.test");
        assertThat(shown.at("/to/2/userId").asLong()).isEqualTo(acmeLogin.getId());
        assertThat(shown.get("readByMe").asBoolean()).isFalse();

        JsonNode inbox = getOk("/api/inbox", acmeLogin).at("/content/0");
        assertThat(inbox.at("/from/name").asText()).isEqualTo("Gene Invoice team");
        assertThat(inbox.at("/from/address").isNull()).isTrue();

        JsonNode staffView = getOk("/api/emails", admin,
                "entityType", "INVOICE", "entityId", inv.getId().toString()).at("/content/0");
        assertThat(staffView.at("/from/masked").asBoolean()).isFalse();
        assertThat(staffView.at("/from/address").asText()).isEqualTo("admin@geneinvoice.local");
        assertThat(staffView.at("/to/0/name").asText()).isEqualTo("SAM.SALES");
        assertThat(staffView.get("readByMe").isNull()).isTrue();
    }

    @Test
    void aSenderRoleIsShownToTheCustomerAsThatRole() throws Exception {
        Invoice inv = invoice(acme, sales);
        send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "from", toRole("SALES_POC", "RECORD")));

        JsonNode shown = getOk("/api/emails", acmeLogin,
                "entityType", "INVOICE", "entityId", inv.getId().toString()).at("/content/0");
        assertThat(shown.at("/from/name").asText()).isEqualTo("Sales POC (this invoice)");
        assertThat(shown.get("fromRoleLabel").asText()).isEqualTo("Sales POC (this invoice)");
    }

    @Test
    void anotherCustomersLoginCannotReadTheRecordsEmails() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer())));
        Customer globex = customer("Globex Corp");
        User globexLogin = customerUser("globex.login", globex.getId());

        // Listing a record's emails resolves the record, which answers a customer login for
        // another customer's invoice exactly as it answers one for an invoice that does not
        // exist — otherwise the difference is an oracle for which ids exist (AUTH-08).
        mockMvc.perform(get("/api/emails").with(as(globexLogin))
                        .param("entityType", "INVOICE").param("entityId", inv.getId().toString()))
                .andExpect(status().isNotFound());
        // Reading the email by id hands back whatever its record answered, for someone who was
        // never a party to it — so it says the same thing the invoice does (AUTH-08).
        mockMvc.perform(get("/api/emails/" + sent.get("id").asLong()).with(as(globexLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRecipientReadsTheEmailEvenWithoutSeeingItsRecord() throws Exception {
        Invoice theirs = invoice(acme, otherSales);
        JsonNode sent = send(admin, email("INVOICE", theirs.getId(), List.of(toUser(sales))));
        long id = sent.get("id").asLong();

        JsonNode read = getOk("/api/emails/" + id, sales);
        assertThat(read.get("readByMe").asBoolean()).isFalse();
        assertThat(read.get("status").asText()).isEqualTo("NOT_SENT");
        assertThat(read.get("canRetry").asBoolean()).isFalse();
        assertThat(read.get("canOpenRecord").asBoolean()).isFalse();
        mockMvc.perform(get("/api/emails").with(as(sales))
                        .param("entityType", "INVOICE").param("entityId", theirs.getId().toString()))
                .andExpect(status().isNotFound());
        assertThat(sent.get("canOpenRecord").asBoolean()).isTrue();
        assertThat(getOk("/api/emails/" + id, admin).get("canRetry").asBoolean()).isTrue();
        assertThat(getOk("/api/emails/" + id, admin).get("canOpenRecord").asBoolean()).isTrue();
        assertThat(getOk("/api/emails", admin, "entityType", "INVOICE", "entityId", theirs.getId().toString())
                .at("/content/0/canOpenRecord").asBoolean()).isTrue();
    }

    @Test
    void someoneWhoIsNotPartyToItReadsItOnlyWhereTheyCanSeeTheRecord() throws Exception {
        Invoice mine = invoice(acme, sales);
        JsonNode aboutInvoice = send(admin, email("INVOICE", mine.getId(), List.of(toUser(collections))));
        JsonNode aboutRole = send(admin, email("ROLE", role("VIEWER").getId(), List.of(toUser(collections))));
        User cashier = userRepository.findByUsername("cashier").orElseThrow();

        mockMvc.perform(get("/api/emails/" + aboutInvoice.get("id").asLong()).with(as(otherSales)))
                .andExpect(status().isNotFound());
        getOk("/api/emails/" + aboutInvoice.get("id").asLong(), sales);
        mockMvc.perform(get("/api/emails/" + aboutRole.get("id").asLong()).with(as(cashier)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/emails/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingNeedsEmailView() throws Exception {
        Invoice inv = invoice(acme, sales);
        User viewer = user("vic.viewer", "VIEWER");
        getOk("/api/emails", viewer, "entityType", "INVOICE", "entityId", inv.getId().toString());

        Role invoicesOnly = roleRepository.findByName("INVOICE_READER_NO_EMAIL").orElseGet(() ->
                roleRepository.save(Role.builder().name("INVOICE_READER_NO_EMAIL")
                        .privileges(new HashSet<>(Set.of(privilegeRepository.findByName("INVOICE_VIEW").orElseThrow())))
                        .build()));
        User reader = user("ivy.reader", invoicesOnly.getName());

        mockMvc.perform(get("/api/emails").with(as(reader))
                        .param("entityType", "INVOICE").param("entityId", inv.getId().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inbox").with(as(reader))).andExpect(status().isForbidden());
    }
}
