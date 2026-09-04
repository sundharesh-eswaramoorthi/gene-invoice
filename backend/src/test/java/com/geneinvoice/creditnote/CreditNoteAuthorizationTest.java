package com.geneinvoice.creditnote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC12 / AC13 plus the inherited customer-scope rule: the server enforces INVOICE_MANAGE for
 * issue/void, INVOICE_VIEW suffices for reads, a customer principal cannot open another
 * customer's credit history, and the history holds active and voided notes with the five
 * recorded facts (amount, reason, issuer, issuance time, voided state).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class CreditNoteAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final AtomicLong SEQ = new AtomicLong();

    private String login(String username, String password) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private void ensureUser(String username, String password, String roleName, Long customerId) {
        if (userRepository.findByUsername(username).isPresent()) return;
        var role = roleRepository.findByName(roleName).orElseThrow();
        userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .fullName("Test " + username)
                .role(role)
                .customerId(customerId)
                .active(true)
                .build());
    }

    private Customer newCustomer() {
        long n = SEQ.incrementAndGet();
        return customerRepository.save(Customer.builder().name("Authz Customer " + n).build());
    }

    private Invoice newInvoice(Customer c, String total) {
        long n = SEQ.incrementAndGet();
        return invoiceRepository.save(Invoice.builder()
                .customer(c)
                .invoiceNumber("CN-AUTH-" + n)
                .total(new BigDecimal(total))
                .paidAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.UNPAID)
                .build());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void serverEnforcesManageForMutationsAndViewSufficesForReads() throws Exception {
        String adminToken = login("admin", "admin123");
        ensureUser("viewer-cn", "pass12345", "VIEWER", null);
        String viewerToken = login("viewer-cn", "pass12345");

        Invoice inv = newInvoice(newCustomer(), "100.00");

        // INVOICE_VIEW may open the invoice's credit-note history
        mockMvc.perform(get("/api/invoices/{id}/credit-notes", inv.getId())
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        // issue and void need INVOICE_MANAGE
        mockMvc.perform(post("/api/invoices/{id}/credit-notes", inv.getId())
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.00,\"reason\":\"damage\"}"))
                .andExpect(status().isForbidden());

        var issued = mockMvc.perform(post("/api/invoices/{id}/credit-notes", inv.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.00,\"reason\":\"damage\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(issued.getResponse().getContentAsString());
        assertThat(body.get("creditNote").get("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(body.get("creditNote").get("reason").asText()).isEqualTo("damage");
        assertThat(body.get("notificationWarning").asBoolean()).isFalse();
        long noteId = body.get("creditNote").get("id").asLong();

        mockMvc.perform(post("/api/invoices/{id}/credit-notes/{noteId}/void", inv.getId(), noteId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());

        // refused mutation changed nothing
        var listRes = mockMvc.perform(get("/api/invoices/{id}/credit-notes", inv.getId())
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listRes.getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("voided").asBoolean()).isFalse();

        // a viewer sees the credit-aware position: credited amount and outstanding balance
        var detailRes = mockMvc.perform(get("/api/invoices/{id}", inv.getId())
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        assertThat(detail.get("creditedAmount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(detail.get("balance").decimalValue()).isEqualByComparingTo("75.00");
    }

    @Test
    void customerPrincipalCannotOpenAnotherCustomersCreditHistory() throws Exception {
        Customer mine = newCustomer();
        Customer theirs = newCustomer();
        ensureUser("cust-cn-a", "pass12345", "CUSTOMER", mine.getId());
        String customerToken = login("cust-cn-a", "pass12345");
        Invoice foreign = newInvoice(theirs, "50.00");

        mockMvc.perform(get("/api/invoices/{id}/credit-notes", foreign.getId())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invoices/{id}/credit-notes", foreign.getId())
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyContainsActiveAndVoidedNotesWithAllRecordedFacts() throws Exception {
        String adminToken = login("admin", "admin123");
        Invoice inv = newInvoice(newCustomer(), "100.00");

        long firstId = issue(adminToken, inv.getId(), "25.00", "  First reason  ");
        long secondId = issue(adminToken, inv.getId(), "10.00", "Second reason");

        mockMvc.perform(post("/api/invoices/{id}/credit-notes/{noteId}/void", inv.getId(), firstId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        var res = mockMvc.perform(get("/api/invoices/{id}/credit-notes", inv.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(2);

        JsonNode first = byId(list, firstId);
        JsonNode second = byId(list, secondId);
        assertThat(first.get("voided").asBoolean()).isTrue();
        assertThat(second.get("voided").asBoolean()).isFalse();

        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(first.get("reason").asText()).isEqualTo("First reason");
        assertThat(second.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(second.get("reason").asText()).isEqualTo("Second reason");

        for (JsonNode note : new JsonNode[]{first, second}) {
            assertThat(note.get("issuedByUserId").isNumber()).isTrue();
            assertThat(note.get("issuedByName").asText()).isEqualTo("System Administrator");
            assertThat(note.get("issuedAt").asText()).isNotBlank();
            assertThat(note.get("invoiceNumber").asText()).isEqualTo(inv.getInvoiceNumber());
        }
    }

    private long issue(String adminToken, long invoiceId, String amount, String reason) throws Exception {
        var res = mockMvc.perform(post("/api/invoices/{id}/credit-notes", invoiceId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("creditNote").get("id").asLong();
    }

    private static JsonNode byId(JsonNode list, long id) {
        for (Iterator<JsonNode> it = list.elements(); it.hasNext(); ) {
            JsonNode n = it.next();
            if (n.get("id").asLong() == id) return n;
        }
        throw new AssertionError("no credit note with id " + id + " in " + list);
    }
}
