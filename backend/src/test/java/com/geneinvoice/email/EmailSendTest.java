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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Composing and sending emails, how recipients are worked out, and the Email tab. */
class EmailSendTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    User admin;
    User cara;
    User cole;
    User sam;
    Role collections;
    Customer acme;
    Customer globex;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        cole = user("cole.collections", DataSeeder.ROLE_COLLECTION_POC);
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = role(DataSeeder.ROLE_COLLECTION_POC);
        collections.setEmail("collections@gene.test");
        roleRepository.save(collections);
        acme = customerRepository.save(Customer.builder().name("Acme Ltd").email("billing@acme.test")
                .additionalEmails(new ArrayList<>(List.of("ap@acme.test"))).build());
        globex = customer("Globex Corp");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    @AfterEach
    void clearRoleMailbox() {
        Role r = role(DataSeeder.ROLE_COLLECTION_POC);
        r.setEmail(null);
        roleRepository.save(r);
    }

    private Invoice invoice(Customer c, User salesPoc) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    // ---- request builders ----------------------------------------------------------

    private static Map<String, Object> from(String type, Long id) {
        return Map.of("type", type, "id", id);
    }

    private static Map<String, Object> to(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> email(String targetKey, Long targetId, Object from, Object to,
                                             String subject) {
        Map<String, Object> m = new HashMap<>();
        if (targetKey != null) m.put(targetKey, targetId);
        m.put("from", from);
        m.put("to", to);
        m.put("subject", subject);
        return m;
    }

    private ResultActions send(User caller, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/emails").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode sent(User caller, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(send(caller, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode tab(User caller, String param, Long id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/emails").param(param, String.valueOf(id))
                        .with(as(caller)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode bulk(User caller, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/emails/bulk").with(as(caller))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Long> inboxOf(JsonNode email) {
        return emailDeliveryRepository.findByEmailId(email.get("id").asLong()).stream()
                .map(EmailDelivery::getUserId).sorted().toList();
    }

    // ---- sending -------------------------------------------------------------------

    @Test
    void anEmailRecordsItsSenderAndEveryKindOfRecipientAsTheyWereWhenSent() throws Exception {
        JsonNode e = sent(admin, email("customerId", acme.getId(),
                from("ROLE", collections.getId()),
                to("userIds", List.of(cara.getId(), sam.getId()),
                        "roleIds", List.of(collections.getId()),
                        "customerEmails", List.of("AP@acme.test")),
                "  Overdue balance  "));

        assertThat(e.get("targetType").asText()).isEqualTo("CUSTOMER");
        assertThat(e.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(e.get("from").get("type").asText()).isEqualTo("ROLE");
        assertThat(e.get("from").get("name").asText()).isEqualTo(DataSeeder.ROLE_COLLECTION_POC);
        assertThat(e.get("from").get("address").asText()).isEqualTo("collections@gene.test");
        assertThat(e.get("subject").asText()).isEqualTo("Overdue balance");
        assertThat(e.get("body").asText()).isEmpty();
        assertThat(e.get("sentByUserId").asLong()).isEqualTo(admin.getId());
        assertThat(e.get("sentByName").asText()).isEqualTo("System Administrator");
        assertThat(e.get("sentAt").isNull()).isFalse();

        JsonNode recipients = e.get("to");
        assertThat(recipients.size()).isEqualTo(4);
        assertThat(recipients.get(0).get("type").asText()).isEqualTo("USER");
        assertThat(recipients.get(0).get("name").asText()).isEqualTo("CARA.COLLECTIONS");
        assertThat(recipients.get(1).get("userId").asLong()).isEqualTo(sam.getId());
        assertThat(recipients.get(2).get("type").asText()).isEqualTo("ROLE");
        assertThat(recipients.get(2).get("address").asText()).isEqualTo("collections@gene.test");
        assertThat(recipients.get(2).get("members").findValuesAsText("name"))
                .containsExactlyInAnyOrder("CARA.COLLECTIONS", "COLE.COLLECTIONS");
        // The address as the customer holds it, not as it was typed.
        assertThat(recipients.get(3).get("type").asText()).isEqualTo("CUSTOMER_EMAIL");
        assertThat(recipients.get(3).get("address").asText()).isEqualTo("ap@acme.test");

        // Cara is named directly and is in the role: one Inbox copy, not two.
        assertThat(inboxOf(e)).containsExactlyInAnyOrder(cara.getId(), sam.getId(), cole.getId());
    }

    @Test
    void aRolesMailboxAndMembersAreThoseAtSendTime() throws Exception {
        JsonNode e = sent(admin, email("customerId", acme.getId(),
                from("ROLE", collections.getId()),
                to("roleIds", List.of(collections.getId())), "Before"));

        Role changed = role(DataSeeder.ROLE_COLLECTION_POC);
        changed.setEmail("recoveries@gene.test");
        roleRepository.save(changed);
        User latecomer = user("lara.late", DataSeeder.ROLE_COLLECTION_POC);

        JsonNode listed = tab(admin, "customerId", acme.getId()).get("content").get(0);
        assertThat(listed.get("id").asLong()).isEqualTo(e.get("id").asLong());
        assertThat(listed.get("from").get("address").asText()).isEqualTo("collections@gene.test");
        assertThat(listed.get("to").get(0).get("members").findValuesAsText("userId"))
                .containsExactlyInAnyOrder(String.valueOf(cara.getId()), String.valueOf(cole.getId()));
        assertThat(inboxOf(e)).doesNotContain(latecomer.getId());

        // The next email is from the mailbox as it is now, and reaches the newcomer.
        JsonNode after = sent(admin, email("customerId", acme.getId(),
                from("ROLE", collections.getId()),
                to("roleIds", List.of(collections.getId())), "After"));
        assertThat(after.get("from").get("address").asText()).isEqualTo("recoveries@gene.test");
        assertThat(inboxOf(after)).contains(latecomer.getId());
    }

    @Test
    void aUserSenderShowsTheirOwnNameAndAddressAndTheBodyIsKept() throws Exception {
        Map<String, Object> body = email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Call back");
        body.put("body", "Line one\nLine two");
        JsonNode e = sent(admin, body);
        assertThat(e.get("from").get("name").asText()).isEqualTo("CARA.COLLECTIONS");
        assertThat(e.get("from").get("address").asText()).isEqualTo("cara.collections@test.local");
        assertThat(e.get("body").asText()).isEqualTo("Line one\nLine two");
        // Sent by whoever is signed in, not by the sender named on the form.
        assertThat(e.get("sentByUserId").asLong()).isEqualTo(admin.getId());
    }

    @Test
    void theFormNeedsASenderARecipientAndASubject() throws Exception {
        Map<String, Object> noFrom = email("customerId", acme.getId(), null,
                to("userIds", List.of(cara.getId())), "Hello");
        send(admin, noFrom).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.from").value("is required"));

        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()), to(), "Hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Add at least one recipient"));

        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.subject").value("is required"));

        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "x".repeat(201)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recipientsAndSendersMustBeRealInternalPeopleAndTheCustomersOwnAddresses() throws Exception {
        Invoice inv = invoice(acme, sam);
        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("customerEmails", List.of("someone@else.test")), "Hi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'someone@else.test' is not an email address of Acme Ltd"));

        User gone = user("gus.gone", DataSeeder.ROLE_COLLECTION_POC);
        gone.setActive(false);
        userRepository.save(gone);
        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(gone.getId())), "Hi"))
                .andExpect(status().isBadRequest());

        User login = customerUser("acme.login", acme.getId());
        send(admin, email("customerId", acme.getId(), from("USER", login.getId()),
                to("userIds", List.of(cara.getId())), "Hi"))
                .andExpect(status().isBadRequest());

        send(admin, email("customerId", acme.getId(), from("ROLE", role("CUSTOMER").getId()),
                to("userIds", List.of(cara.getId())), "Hi"))
                .andExpect(status().isBadRequest());

        Map<String, Object> both = email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Hi");
        both.put("invoiceId", inv.getId());
        send(admin, both).andExpect(status().isBadRequest());
    }

    @Test
    void anEmailThatWouldReachNobodyIsRefused() throws Exception {
        String emptyRole = "EMAIL_EMPTY_ROLE";
        Role empty = roleRepository.findByName(emptyRole)
                .orElseGet(() -> roleRepository.save(Role.builder().name(emptyRole).build()));
        send(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("roleIds", List.of(empty.getId())), "Hi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No one would receive this email: EMAIL_EMPTY_ROLE has no active members"));

        send(admin, email("customerId", globex.getId(), from("USER", cara.getId()),
                to("allCustomerEmails", true), "Hi"))
                .andExpect(status().isBadRequest());

        // An empty role alongside the customer's addresses is fine: someone still receives it.
        sent(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("roleIds", List.of(empty.getId()), "allCustomerEmails", true), "Hi"));
    }

    // ---- invoices and the Email tab ------------------------------------------------

    @Test
    void anInvoiceEmailGoesToItsCustomersAddressesAndIsListedOnBothTabsNewestFirst() throws Exception {
        Invoice inv = invoice(acme, sam);

        mockMvc.perform(get("/api/emails/addresses").param("invoiceId", String.valueOf(inv.getId()))
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.invoiceNumber").value(inv.getInvoiceNumber()))
                .andExpect(jsonPath("$.addresses[0]").value("billing@acme.test"))
                .andExpect(jsonPath("$.addresses[1]").value("ap@acme.test"));

        JsonNode first = sent(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "About the account"));
        JsonNode aboutInvoice = sent(admin, email("invoiceId", inv.getId(), from("USER", cara.getId()),
                to("allCustomerEmails", true), "About the invoice"));

        assertThat(aboutInvoice.get("targetType").asText()).isEqualTo("INVOICE");
        assertThat(aboutInvoice.get("invoiceNumber").asText()).isEqualTo(inv.getInvoiceNumber());
        assertThat(aboutInvoice.get("to").findValuesAsText("address"))
                .containsExactly("billing@acme.test", "ap@acme.test");
        // Customer addresses have no Inbox.
        assertThat(inboxOf(aboutInvoice)).isEmpty();

        JsonNode customerTab = tab(admin, "customerId", acme.getId());
        assertThat(customerTab.get("totalElements").asInt()).isEqualTo(2);
        assertThat(customerTab.get("content").get(0).get("id").asLong()).isEqualTo(aboutInvoice.get("id").asLong());
        assertThat(customerTab.get("content").get(0).get("invoiceNumber").asText()).isEqualTo(inv.getInvoiceNumber());
        assertThat(customerTab.get("content").get(1).get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(customerTab.get("content").get(1).get("invoiceNumber").isNull()).isTrue();

        JsonNode invoiceTab = tab(admin, "invoiceId", inv.getId());
        assertThat(invoiceTab.get("totalElements").asInt()).isEqualTo(1);
        assertThat(invoiceTab.get("content").get(0).get("id").asLong()).isEqualTo(aboutInvoice.get("id").asLong());
    }

    @Test
    void theTabPagesWithTheUsualPageSizes() throws Exception {
        for (int i = 0; i < 12; i++) {
            sent(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                    to("userIds", List.of(cole.getId())), "Note " + i));
        }
        mockMvc.perform(get("/api/emails").param("customerId", String.valueOf(acme.getId()))
                        .param("size", "10").param("page", "1").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[1].subject").value("Note 0"));
        mockMvc.perform(get("/api/emails").param("customerId", String.valueOf(acme.getId()))
                        .param("size", "7").with(as(admin)))
                .andExpect(status().isBadRequest());
    }

    // ---- bulk ----------------------------------------------------------------------

    @Test
    void aBulkSendCreatesOneEmailPerRowAndSkipsRowsNoOneWouldReceive() throws Exception {
        Map<String, Object> onlyCustomers = new HashMap<>();
        onlyCustomers.put("targetType", "CUSTOMER");
        onlyCustomers.put("ids", List.of(acme.getId(), globex.getId()));
        onlyCustomers.put("email", email(null, null, from("USER", cara.getId()),
                to("allCustomerEmails", true), "Statement"));

        JsonNode result = bulk(admin, onlyCustomers);
        assertThat(result.get("requested").asInt()).isEqualTo(2);
        assertThat(result.get("succeeded").size()).isEqualTo(1);
        assertThat(result.get("succeeded").get(0).asLong()).isEqualTo(acme.getId());
        assertThat(result.get("skipped").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("id").asLong()).isEqualTo(globex.getId());
        assertThat(result.get("skipped").get(0).get("reason").asText())
                .isEqualTo("Globex Corp has no email address, and no one else in To would receive it");

        // With someone else in To, the customer without an address still gets its email.
        Map<String, Object> withUser = new HashMap<>(onlyCustomers);
        withUser.put("email", email(null, null, from("USER", cara.getId()),
                to("allCustomerEmails", true, "userIds", List.of(cole.getId())), "Statement"));
        JsonNode second = bulk(admin, withUser);
        assertThat(second.get("succeeded").size()).isEqualTo(2);
        assertThat(second.get("skipped").size()).isZero();

        JsonNode globexEmail = tab(admin, "customerId", globex.getId()).get("content").get(0);
        assertThat(globexEmail.get("to").size()).isEqualTo(1);
        assertThat(globexEmail.get("to").get(0).get("userId").asLong()).isEqualTo(cole.getId());
        JsonNode acmeEmail = tab(admin, "customerId", acme.getId()).get("content").get(0);
        assertThat(acmeEmail.get("to").findValuesAsText("address"))
                .contains("billing@acme.test", "ap@acme.test");
    }

    @Test
    void aBulkSendOverInvoicesLinksEachEmailToItsInvoiceAndItsOwnCustomer() throws Exception {
        Invoice acmeInvoice = invoice(acme, sam);
        Invoice globexInvoice = invoice(globex, sam);

        Map<String, Object> body = new HashMap<>();
        body.put("targetType", "INVOICE");
        body.put("ids", List.of(acmeInvoice.getId(), globexInvoice.getId()));
        body.put("email", email(null, null, from("ROLE", collections.getId()),
                to("allCustomerEmails", true), "Reminder"));
        JsonNode result = bulk(admin, body);
        assertThat(result.get("succeeded").size()).isEqualTo(1);
        assertThat(result.get("skipped").get(0).get("reason").asText())
                .startsWith(globexInvoice.getInvoiceNumber() + "'s customer (Globex Corp) has no email address");

        JsonNode onInvoice = tab(admin, "invoiceId", acmeInvoice.getId()).get("content").get(0);
        assertThat(onInvoice.get("customerId").asLong()).isEqualTo(acme.getId());
        assertThat(onInvoice.get("invoiceNumber").asText()).isEqualTo(acmeInvoice.getInvoiceNumber());

        // Particular addresses mean nothing across rows with different customers.
        body.put("email", email(null, null, from("ROLE", collections.getId()),
                to("customerEmails", List.of("billing@acme.test")), "Reminder"));
        mockMvc.perform(post("/api/emails/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());
    }

    // ---- who may do what -----------------------------------------------------------

    @Test
    void aCustomerLoginHasNoEmailAccessAndAViewerMayReadButNotSend() throws Exception {
        User login = customerUser("acme.login", acme.getId());
        mockMvc.perform(get("/api/emails").param("customerId", String.valueOf(acme.getId())).with(as(login)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inbox").with(as(login))).andExpect(status().isForbidden());
        send(login, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Hi")).andExpect(status().isForbidden());

        User viewer = user("vic.viewer", "VIEWER");
        mockMvc.perform(get("/api/emails").param("customerId", String.valueOf(acme.getId())).with(as(viewer)))
                .andExpect(status().isOk());
        send(viewer, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Hi")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/emails/staff").with(as(viewer))).andExpect(status().isForbidden());
    }

    @Test
    void aSalesPocHeldToTheirBookReachesOnlyItsCustomersAndInvoices() throws Exception {
        User sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        Invoice samsInvoice = invoice(acme, sam);
        Invoice sidsInvoice = invoice(acme, sid);
        sent(admin, email("customerId", acme.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Account"));
        sent(admin, email("invoiceId", samsInvoice.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Sam's invoice"));
        sent(admin, email("invoiceId", sidsInvoice.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId())), "Sid's invoice"));

        JsonNode samsView = tab(sam, "customerId", acme.getId());
        assertThat(samsView.get("content").findValuesAsText("subject"))
                .containsExactly("Sam's invoice", "Account");

        mockMvc.perform(get("/api/emails").param("invoiceId", String.valueOf(sidsInvoice.getId())).with(as(sam)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emails").param("customerId", String.valueOf(globex.getId())).with(as(sam)))
                .andExpect(status().isNotFound());
        send(sam, email("customerId", globex.getId(), from("USER", sam.getId()),
                to("userIds", List.of(cole.getId())), "Hi")).andExpect(status().isNotFound());
        sent(sam, email("invoiceId", samsInvoice.getId(), from("USER", sam.getId()),
                to("userIds", List.of(cole.getId())), "Mine"));
    }

    // ---- the compose form's choices ------------------------------------------------

    @Test
    void theFormOffersActiveInternalUsersAndEveryRoleButCustomer() throws Exception {
        customerUser("carl.customer", acme.getId());
        User gone = user("cass.gone", DataSeeder.ROLE_COLLECTION_POC);
        gone.setActive(false);
        userRepository.save(gone);

        JsonNode staff = objectMapper.readTree(mockMvc.perform(get("/api/emails/staff").param("q", "ca")
                        .with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(staff.findValuesAsText("username")).contains("cara.collections")
                .doesNotContain("carl.customer", "cass.gone");

        JsonNode roles = objectMapper.readTree(mockMvc.perform(get("/api/emails/roles").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(roles.findValuesAsText("name")).contains("ADMIN", DataSeeder.ROLE_COLLECTION_POC)
                .doesNotContain("CUSTOMER");
        for (JsonNode r : roles) {
            if (r.get("name").asText().equals(DataSeeder.ROLE_COLLECTION_POC)) {
                assertThat(r.get("memberCount").asLong()).isEqualTo(2);
                assertThat(r.get("email").asText()).isEqualTo("collections@gene.test");
            }
        }
    }

    @Test
    void deletingACustomerTakesItsEmailsOutOfEveryInbox() throws Exception {
        Customer lone = customerRepository.save(Customer.builder().name("Lone Co").email("lone@co.test").build());
        sent(admin, email("customerId", lone.getId(), from("USER", cara.getId()),
                to("userIds", List.of(cole.getId()), "allCustomerEmails", true), "Bye"));
        assertThat(emailDeliveryRepository.countByUserIdAndReadAtIsNull(cole.getId())).isEqualTo(1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/customers/" + lone.getId()).with(as(admin)))
                .andExpect(status().isOk());
        assertThat(emailRepository.findByCustomerId(lone.getId())).isEmpty();
        assertThat(emailDeliveryRepository.countByUserIdAndReadAtIsNull(cole.getId())).isZero();
    }
}
