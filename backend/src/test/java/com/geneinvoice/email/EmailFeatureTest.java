package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The stored in-app Email feature: send-time snapshots, per-record linking, Inbox state. */
class EmailFeatureTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    User admin;
    User sales;
    User collections;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        acme.setEmail("billing@acme.test");
        acme = customerRepository.save(acme);
        globex = customer("Globex Corp");   // deliberately no email address
        widget = product("Widget", "10.00");
        actAs(admin);
    }

    private Role emptyRole(String name) {
        return roleRepository.save(Role.builder().name(name).privileges(new HashSet<>()).build());
    }

    private Invoice invoice(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, BigDecimal.TEN))));
    }

    private JsonNode sendCustomer(Long customerId, Map<String, Object> draft) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/customers/" + customerId + "/emails")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode sendInvoice(Long invoiceId, Map<String, Object> draft) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices/" + invoiceId + "/emails")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> draft(Long fromUser, Long fromRole, List<Long> toUsers,
                                      List<Long> toRoles, boolean includeCustomer,
                                      String subject, String body) {
        return new java.util.LinkedHashMap<>() {{
            if (fromUser != null) put("fromUserId", fromUser);
            if (fromRole != null) put("fromRoleId", fromRole);
            if (toUsers != null) put("toUserIds", toUsers);
            if (toRoles != null) put("toRoleIds", toRoles);
            put("includeCustomerAddress", includeCustomer);
            if (subject != null) put("subject", subject);
            if (body != null) put("body", body);
        }};
    }

    // ---- AC1: stored, linked, no delivery ---------------------------------

    @Test
    void aSuccessfulSendStoresOneEmailLinkedToExactlyThatCustomer() throws Exception {
        JsonNode outcome = sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(collections.getId()), null, false,
                        "Statement ready", "See attached figures."));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        List<Email> stored = emailRepository.findAll();
        assertThat(stored).hasSize(1);
        Email email = stored.get(0);
        assertThat(email.getCustomer().getId()).isEqualTo(acme.getId());
        assertThat(email.getInvoice()).isNull();
        assertThat(email.getSubject()).isEqualTo("Statement ready");
        assertThat(email.getBody()).isEqualTo("See attached figures.");
        // No mail transport exists in this build; persistence is the whole action.
    }

    // ---- AC5 / AC6: From selection and fallback ----------------------------

    @Test
    void aSendWithoutFromIsRefused() throws Exception {
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/emails")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(null, null, List.of(sales.getId()), null, false,
                                "Hello", null))))
                .andExpect(status().isBadRequest());
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void aRoleSenderKeepsTheRoleNameAndUsesItsAddressAtSendTime() throws Exception {
        Role support = emptyRole("SUPPORT_TEAM");
        support.setEmail("support@geneinvoice.test");
        support = roleRepository.save(support);

        JsonNode outcome = sendCustomer(acme.getId(),
                draft(null, support.getId(), List.of(sales.getId()), null, false, "Hi", null));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        Email email = emailRepository.findAll().get(0);
        assertThat(email.getSenderDisplay()).isEqualTo("SUPPORT_TEAM");
        assertThat(email.getSenderAddress()).isEqualTo("support@geneinvoice.test");
    }

    @Test
    void aRoleWithoutAddressUsesTheConfiguredAdminFallbackButKeepsTheRoleName() throws Exception {
        mockMvc.perform(put("/api/settings/admin-email").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("adminEmail", "ops@geneinvoice.test"))))
                .andExpect(status().isOk());
        Role fallback = emptyRole("FALLBACK_ROLE");

        JsonNode outcome = sendCustomer(acme.getId(),
                draft(null, fallback.getId(), List.of(sales.getId()), null, false, "Hi", null));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        Email email = emailRepository.findAll().get(0);
        assertThat(email.getSenderDisplay()).isEqualTo("FALLBACK_ROLE");
        assertThat(email.getSenderAddress()).isEqualTo("ops@geneinvoice.test");
    }

    @Test
    void aSendNeedingAnUnsetFallbackIsRejectedButAResolvedSenderIsNot() throws Exception {
        Role fallback = emptyRole("FALLBACK_ROLE");

        mockMvc.perform(post("/api/customers/" + acme.getId() + "/emails")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(null, fallback.getId(), List.of(sales.getId()), null,
                                false, "Hi", null))))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message", org.hamcrest.Matchers
                                .containsString("application-wide admin email")));
        assertThat(emailRepository.count()).isZero();

        // The unset setting blocks only sends that need it: a user with an address sends fine.
        JsonNode outcome = sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(collections.getId()), null, false, "Hi", null));
        assertThat(outcome.get("created").asBoolean()).isTrue();
    }

    // ---- AC7 / AC8 / AC14 / AC15: draft validation -------------------------

    @Test
    void aSendWithNoRecipientSelectionIsRefused() throws Exception {
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/emails")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(sales.getId(), null, List.of(), List.of(), false,
                                "Hello", null))))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message", org.hamcrest.Matchers
                                .containsString("at least one recipient")));
    }

    @Test
    void aSingleSendResolvingToNoRecipientsCreatesNothingAndSaysSo() throws Exception {
        Role empty = emptyRole("NOBODY_HOME");

        JsonNode outcome = sendCustomer(globex.getId(),
                draft(sales.getId(), null, null, List.of(empty.getId()), true, "Hi", null));

        assertThat(outcome.get("created").asBoolean()).isFalse();
        assertThat(outcome.get("emailId").isNull()).isTrue();
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void blankMissingAndWhitespaceOnlySubjectsAreRefused() throws Exception {
        for (String subject : new String[]{null, "", "   "}) {
            mockMvc.perform(post("/api/customers/" + acme.getId() + "/emails")
                            .with(as(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(sales.getId(), null, List.of(admin.getId()), null,
                                    false, subject, null))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(emailRepository.count()).isZero();
    }

    @Test
    void anEmailMayBeCreatedWithNoBodyOrAnEmptyBody() throws Exception {
        JsonNode first = sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "No body", null));
        JsonNode second = sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "Empty body", ""));

        assertThat(first.get("created").asBoolean()).isTrue();
        assertThat(second.get("created").asBoolean()).isTrue();
        assertThat(emailRepository.count()).isEqualTo(2);
    }

    // ---- AC9 / AC10 / AC25: recipient snapshots ------------------------------

    @Test
    void aUserPickedDirectlyAndThroughASelectedRoleAppearsOnce() throws Exception {
        Role salesRole = role(DataSeeder.ROLE_SALES_POC);   // sales belongs to it

        JsonNode outcome = sendCustomer(acme.getId(), draft(sales.getId(), null,
                List.of(sales.getId(), collections.getId()), List.of(salesRole.getId()), false,
                "Overlap", null));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        Email email = emailRepository.findAll().get(0);
        List<EmailRecipient> recipients = emailRecipientRepository.findByEmail_Id(email.getId());
        long forSales = recipients.stream().filter(r -> sales.getId().equals(r.getUserId())).count();
        assertThat(forSales).isEqualTo(1);
        assertThat(recipients.stream().filter(r -> sales.getId().equals(r.getUserId())).findFirst()
                .orElseThrow().getKind()).isEqualTo(EmailRecipientKind.ROLE);
    }

    @Test
    void roleRecipientsAreFrozenAtSendTimeAndLaterJoinersAreNotAdded() throws Exception {
        Role empty = emptyRole("FROZEN_WATCHERS");

        JsonNode outcome = sendCustomer(acme.getId(), draft(sales.getId(), null,
                List.of(collections.getId()), List.of(empty.getId()), false, "Frozen", null));
        Long emailId = outcome.get("emailId").asLong();

        // A brand-new member joins the selected role after the send: the stored Email must
        // not change (AC10).
        User latecomer = user("laura.late", "FROZEN_WATCHERS");

        MvcResult detailResult = mockMvc.perform(get("/api/emails/" + emailId)
                        .with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        List<String> labels = new java.util.ArrayList<>();
        for (JsonNode r : detail.get("recipients")) labels.add(r.get("label").asText());
        assertThat(labels).doesNotContain("LAURA.LATE");
        assertThat(labels).hasSize(1);   // only the directly named collections user

        // No Inbox row exists for the late joiner on this Email.
        assertThat(emailRecipientReadRepository.findAll().stream()
                .filter(r -> r.getEmail().getId().equals(emailId))
                .map(EmailRecipientRead::getUserId))
                .doesNotContain(latecomer.getId());
        // The role snapshot kept its send-time member list: empty.
        assertThat(emailRecipientRepository.findByEmail_Id(emailId).stream()
                .filter(r -> r.getKind() == EmailRecipientKind.ROLE)).isEmpty();

    }

    @Test
    void occurrencesKeepTheirSelectedRoleLabelsSeparately() throws Exception {
        Role watchers = emptyRole("WATCHERS");
        User uma = userRepository.save(User.builder()
                .username("uma.watcher").email("uma@test.local").fullName("UMA.WATCHER")
                .password(passwordEncoder.encode("password")).role(watchers).active(true).build());

        sendCustomer(acme.getId(), draft(sales.getId(), null, null,
                List.of(watchers.getId(), role(DataSeeder.ROLE_SALES_POC).getId()), false,
                "Two roles", null));

        Email email = emailRepository.findAll().get(0);
        List<EmailRecipient> recipients = emailRecipientRepository.findByEmail_Id(email.getId());
        assertThat(recipients.stream().filter(r -> r.getKind() == EmailRecipientKind.ROLE))
                .hasSize(2);
        assertThat(recipients.stream()
                .filter(r -> r.getUserId().equals(uma.getId())).findFirst().orElseThrow()
                .getRoleName()).isEqualTo("WATCHERS");
        assertThat(recipients.stream()
                .filter(r -> r.getUserId().equals(sales.getId())).findFirst().orElseThrow()
                .getRoleName()).isEqualTo(DataSeeder.ROLE_SALES_POC);
    }

    @Test
    void aCustomerAddressRecipientGetsNoInboxState() throws Exception {
        sendCustomer(acme.getId(), draft(sales.getId(), null, null, null, true,
                "Customer only", null));

        Email email = emailRepository.findAll().get(0);
        List<EmailRecipient> recipients = emailRecipientRepository.findByEmail_Id(email.getId());
        assertThat(recipients).hasSize(1);
        assertThat(recipients.get(0).getKind()).isEqualTo(EmailRecipientKind.CUSTOMER_ADDRESS);
        assertThat(recipients.get(0).getAddress()).isEqualTo("billing@acme.test");
        assertThat(recipients.get(0).getUserId()).isNull();
        assertThat(emailRecipientReadRepository.findAll()).isEmpty();

        // Nobody's Inbox can see it (AC25).
        MvcResult inbox = mockMvc.perform(get("/api/emails/inbox").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode page = objectMapper.readTree(inbox.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    // ---- AC12 / AC13: per-row Customer address resolution -------------------

    @Test
    void anInvoiceSendResolvesThatInvoicesCustomerAddress() throws Exception {
        Invoice inv = invoice(acme);

        JsonNode outcome = sendInvoice(inv.getId(),
                draft(sales.getId(), null, null, null, true, "Your invoice", null));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        Email email = emailRepository.findAll().get(0);
        assertThat(email.getInvoice().getId()).isEqualTo(inv.getId());
        assertThat(emailRecipientRepository.findByEmail_Id(email.getId()).get(0).getAddress())
                .isEqualTo("billing@acme.test");
    }

    @Test
    void aMissingCustomerAddressIsOmittedAndRemainingRecipientsStillSend() throws Exception {
        JsonNode outcome = sendCustomer(globex.getId(),   // globex has no email
                draft(sales.getId(), null, List.of(collections.getId()), null, true, "Hi", null));

        assertThat(outcome.get("created").asBoolean()).isTrue();
        Email email = emailRepository.findAll().get(0);
        List<EmailRecipient> recipients = emailRecipientRepository.findByEmail_Id(email.getId());
        assertThat(recipients).hasSize(1);
        assertThat(recipients.get(0).getKind()).isEqualTo(EmailRecipientKind.DIRECT);
    }

    // ---- AC3 / AC4: bulk sends ----------------------------------------------

    @Test
    void aBulkSendCreatesPerRowEmailsAndCountsZeroRecipientRowsAsSkipped() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/customers/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "action", "SEND_EMAIL",
                                "ids", List.of(acme.getId(), globex.getId()),
                                "params", Map.of(
                                        "fromUserId", sales.getId(),
                                        "includeCustomerAddress", true,
                                        "subject", "Bulk hello")))))
                .andExpect(status().isOk()).andReturn();
        JsonNode bulk = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(bulk.get("requested").asInt()).isEqualTo(2);
        assertThat(bulk.get("succeeded").toString()).contains(acme.getId().toString());
        assertThat(bulk.get("skipped").toString()).contains(globex.getId().toString());

        // One Email, linked to the one recipient-bearing row; none for the skipped row.
        List<Email> stored = emailRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCustomer().getId()).isEqualTo(acme.getId());
    }

    // ---- AC16 / AC18 / AC19 / AC17: record tabs and detail ------------------

    @Test
    void theCustomerTabRollsUpOwnAndInvoiceEmailsNewestFirstWithInvoiceNumbers() throws Exception {
        Invoice inv = invoice(acme);
        sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "First", null));
        Thread.sleep(5);   // distinct sentAt values, so newest-first order is observable
        sendInvoice(inv.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "Second", null));

        MvcResult tab = mockMvc.perform(get("/api/customers/" + acme.getId() + "/emails")
                        .with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(tab.getResponse().getContentAsString());

        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).get("subject").asText()).isEqualTo("Second");
        assertThat(rows.get(0).get("link").asText()).isEqualTo("INVOICE");
        assertThat(rows.get(0).get("invoiceNumber").asText()).isEqualTo(inv.getInvoiceNumber());
        assertThat(rows.get(1).get("subject").asText()).isEqualTo("First");
        assertThat(rows.get(1).get("link").asText()).isEqualTo("CUSTOMER");
    }

    @Test
    void theInvoiceTabListsOnlyEmailsLinkedToThatInvoice() throws Exception {
        Invoice first = invoice(acme);
        Invoice second = invoice(acme);
        sendInvoice(first.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "One", null));
        sendCustomer(acme.getId(),
                draft(sales.getId(), null, List.of(admin.getId()), null, false, "Other", null));

        MvcResult tab = mockMvc.perform(get("/api/invoices/" + second.getId() + "/emails")
                        .with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(tab.getResponse().getContentAsString()).size()).isZero();

        MvcResult tabFirst = mockMvc.perform(get("/api/invoices/" + first.getId() + "/emails")
                        .with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(tabFirst.getResponse().getContentAsString());
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("subject").asText()).isEqualTo("One");
    }

    @Test
    void openingAnEmailShowsTheStoredFactsAndMarksOnlyTheCallerRead() throws Exception {
        JsonNode outcome = sendCustomer(acme.getId(), draft(sales.getId(), null,
                List.of(admin.getId(), collections.getId()), null, true, "Full view", "Body text"));
        Long emailId = outcome.get("emailId").asLong();

        MvcResult detailResult = mockMvc.perform(get("/api/emails/" + emailId)
                        .with(as(collections)))
                .andExpect(status().isOk()).andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        assertThat(detail.get("subject").asText()).isEqualTo("Full view");
        assertThat(detail.get("body").asText()).isEqualTo("Body text");
        assertThat(detail.get("senderDisplay").asText()).isEqualTo("SAM.SALES");
        assertThat(detail.get("senderAddress").asText()).isEqualTo("sam.sales@test.local");
        assertThat(detail.get("sentByDisplay").isNull()).isFalse();
        assertThat(detail.get("sentAt").isNull()).isFalse();
        assertThat(detail.get("recipients").size()).isEqualTo(3);   // admin, cara, customer address
        assertThat(detail.get("read").asBoolean()).isTrue();         // opened by a recipient

        // collections is now read; admin's independent row is still unread (AC21).
        MvcResult adminInbox = mockMvc.perform(get("/api/emails/inbox").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode adminRow = objectMapper.readTree(adminInbox.getResponse().getContentAsString())
                .get("content").get(0);
        assertThat(adminRow.get("read").asBoolean()).isFalse();

        MvcResult collectionsInbox = mockMvc.perform(get("/api/emails/inbox")
                        .with(as(collections)))
                .andExpect(status().isOk()).andReturn();
        JsonNode collectionsRows = objectMapper
                .readTree(collectionsInbox.getResponse().getContentAsString());
        assertThat(collectionsRows.get("totalElements").asLong()).isEqualTo(1);
        assertThat(collectionsRows.get("content").get(0).get("read").asBoolean()).isTrue();
    }

    // ---- AC23 / EDGE3: read actions ------------------------------------------

    @Test
    void markAllReadCoversEveryPageNotJustTheVisibleOne() throws Exception {
        for (int i = 0; i < 3; i++) {
            sendCustomer(acme.getId(), draft(sales.getId(), null, List.of(admin.getId()), null,
                    false, "Batch " + i, null));
        }

        // A single-row mark touches only that Email.
        List<Email> stored = emailRepository.findAll();
        mockMvc.perform(post("/api/emails/" + stored.get(0).getId() + "/read").with(as(admin)))
                .andExpect(status().isOk());
        assertThat(emailRecipientReadRepository.findAll().stream().filter(r -> !r.isRead()).count())
                .isEqualTo(2);

        MvcResult all = mockMvc.perform(post("/api/emails/mark-all-read").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(all.getResponse().getContentAsString())
                .get("updated").asInt()).isEqualTo(2);
        assertThat(emailRecipientReadRepository.findAll().stream().noneMatch(r -> !r.isRead()))
                .isTrue();

        // The other user's rows are not the caller's to mark.
        sendCustomer(acme.getId(), draft(sales.getId(), null,
                List.of(admin.getId(), collections.getId()), null, false, "Fresh", null));
        mockMvc.perform(post("/api/emails/mark-all-read").with(as(collections)))
                .andExpect(status().isOk());
        assertThat(emailRecipientReadRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(admin.getId())).allMatch(EmailRecipientRead::isRead))
                .isFalse();
    }

    @Test
    void theInboxListsOnlyTheCallersEmailsNewestFirst() throws Exception {
        sendCustomer(acme.getId(), draft(sales.getId(), null, List.of(admin.getId()), null,
                false, "For admin", null));
        sendCustomer(acme.getId(), draft(sales.getId(), null, List.of(collections.getId()), null,
                false, "Not for admin", null));

        MvcResult inbox = mockMvc.perform(get("/api/emails/inbox").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode page = objectMapper.readTree(inbox.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("subject").asText()).isEqualTo("For admin");
        assertThat(page.get("content").get(0).get("read").asBoolean()).isFalse();
    }

    // ---- AC24 / DES-EMAIL-12: Inbox pagination contract ----------------------

    @Test
    void theInboxPublishesItsOwnPageSizesAndDefaultsToTwentyFive() throws Exception {
        MvcResult inbox = mockMvc.perform(get("/api/emails/inbox").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(inbox.getResponse().getContentAsString())
                .get("size").asInt()).isEqualTo(25);

        MvcResult schema = mockMvc.perform(get("/api/table-schemas/inbox").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode inboxSchema = objectMapper.readTree(schema.getResponse().getContentAsString());
        assertThat(inboxSchema.get("pageSizes").toString()).isEqualTo("[10,25,50,100]");
        assertThat(inboxSchema.get("defaultPageSize").asInt()).isEqualTo(25);

        // A size outside the Inbox's own set is refused, while existing tables are unchanged.
        mockMvc.perform(get("/api/emails/inbox").with(as(admin)).param("size", "20"))
                .andExpect(status().isBadRequest());
        MvcResult invoices = mockMvc.perform(get("/api/table-schemas/invoices").with(as(admin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode invoiceSchema = objectMapper.readTree(invoices.getResponse().getContentAsString());
        assertThat(invoiceSchema.get("pageSizes").toString()).isEqualTo("[10,20,50]");
        assertThat(invoiceSchema.get("defaultPageSize").asInt()).isEqualTo(20);
    }

    // ---- AC6 route: the setting is administrator-maintained ------------------

    @Test
    void theAdminEmailSettingIsEditableByAdminsAndRoundTrips() throws Exception {
        mockMvc.perform(get("/api/settings/admin-email").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.adminEmail").isEmpty());

        mockMvc.perform(put("/api/settings/admin-email").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("adminEmail", "  ops@geneinvoice.test  "))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.adminEmail").value("ops@geneinvoice.test"));

        mockMvc.perform(put("/api/settings/admin-email").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("adminEmail", "not-an-email"))))
                .andExpect(status().isBadRequest());

        // A role-less-privilege user cannot touch it.
        User viewer = user("vera.viewer", "VIEWER");
        mockMvc.perform(put("/api/settings/admin-email").with(as(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("adminEmail", "x@geneinvoice.test"))))
                .andExpect(status().isForbidden());
    }

    // ---- DES-EMAIL-05: role email address ------------------------------------

    @Test
    void roleEmailAddressRoundTripsThroughTheRoleApiNormalized() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/roles").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "BILLING_DESK",
                                "description", "Billing desk",
                                "email", "  billing@geneinvoice.test  ",
                                "privileges", List.of()))))
                .andExpect(status().isOk()).andReturn();
        JsonNode role = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(role.get("email").asText()).isEqualTo("billing@geneinvoice.test");

        mockMvc.perform(put("/api/roles/" + role.get("id").asLong()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "BILLING_DESK",
                                "description", "Billing desk",
                                "email", "   ",
                                "privileges", List.of()))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.email").isEmpty());

        // Not unique: another role may hold the same address.
        mockMvc.perform(post("/api/roles").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "BILLING_DESK_2",
                                "email", "billing@geneinvoice.test",
                                "privileges", List.of()))))
                .andExpect(status().isOk());
    }
}
