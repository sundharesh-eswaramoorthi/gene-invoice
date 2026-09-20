package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * People, a customer and request helpers the email tests share. The customer has its own address
 * and a login with a different one, so "customer emails" means two addresses unless a test says
 * otherwise.
 */
abstract class EmailTestBase extends IntegrationTestBase {

    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PocService pocService;

    protected User admin;
    protected User sales;
    protected User otherSales;
    protected User collections;
    protected User success;
    protected Customer acme;
    protected User acmeLogin;
    protected Product widget;

    @BeforeEach
    void emailFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        success = user("sue.success", DataSeeder.ROLE_SUCCESS_POC);
        acme = customer("Acme Ltd", "ap@acme.test");
        acmeLogin = customerUser("acme.login", acme.getId());
        widget = product("Widget", "1200.00");
        actAs(admin);
    }

    // ---- records ---------------------------------------------------------------

    protected Invoice invoice(Customer customer, User salesPoc) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    /** Seats the holder as the customer's primary of that kind. */
    protected void seat(Customer customer, PocType type, User holder) {
        actAs(admin);
        pocService.add(customer.getId(), type, holder.getId(), true);
    }

    /** Seats the holder beside the primary, who stays primary (the first seat of a kind is primary anyway). */
    protected void extraSeat(Customer customer, PocType type, User holder) {
        actAs(admin);
        pocService.add(customer.getId(), type, holder.getId(), false);
    }

    /**
     * Three Collection POC seats on the customer: {@link #collections} is primary although seated
     * last, so the primary comes first by being primary, not by age; cora was seated first and is
     * active; cody is inactive. Returns cora and cody (once per test: the usernames are fixed).
     */
    protected List<User> threeCollectionSeats(Customer customer) {
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        extraSeat(customer, PocType.COLLECTION, cora);
        extraSeat(customer, PocType.COLLECTION, cody);
        seat(customer, PocType.COLLECTION, collections);
        deactivate(cody);
        return List.of(cora, cody);
    }

    protected void deactivate(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        fresh.setActive(false);
        userRepository.save(fresh);
    }

    protected void reactivate(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        fresh.setActive(true);
        userRepository.save(fresh);
    }

    // ---- tokens and bodies -----------------------------------------------------------

    protected static Map<String, Object> toUser(User u) {
        return Map.of("type", "USER", "userId", u.getId());
    }

    /** A role without a level, as a form written before levels existed sends it (L7). */
    protected static Map<String, Object> toRole(String role) {
        return Map.of("type", "ROLE", "role", role);
    }

    /** A role at one level: {@code CUSTOMER} for the customer's book, {@code RECORD} for the record's own POC. */
    protected static Map<String, Object> toRole(String role, String level) {
        return Map.of("type", "ROLE", "role", role, "level", level);
    }

    protected static Map<String, Object> toCustomer() {
        return Map.of("type", "CUSTOMER");
    }

    /** A send request; extra key/value pairs override or add to the defaults. */
    protected static Map<String, Object> email(String entityType, Long entityId, List<?> to, Object... extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", entityType);
        body.put("entityId", entityId);
        body.put("to", to);
        body.put("subject", "About your account");
        body.put("body", "Hello");
        for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
        return body;
    }

    // ---- requests ------------------------------------------------------------------

    protected ResultActions postJson(String path, User caller, Object body) throws Exception {
        return mockMvc.perform(post(path).with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected JsonNode send(User caller, Map<String, Object> body) throws Exception {
        return read(postJson("/api/emails", caller, body).andExpect(status().isOk()));
    }

    protected JsonNode getOk(String path, User caller, String... params) throws Exception {
        var request = get(path).with(as(caller));
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return read(mockMvc.perform(request).andExpect(status().isOk()));
    }

    /** JSON is UTF-8 (₹ in a suggestion), which MockMvc does not assume without a charset parameter. */
    protected JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected List<EmailRecipient> recipientsOf(long emailId) {
        return emailRecipientRepository.findByEmailIdOrderByIdAsc(emailId);
    }
}
