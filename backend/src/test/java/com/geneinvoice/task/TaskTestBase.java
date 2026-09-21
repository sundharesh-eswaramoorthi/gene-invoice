package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The people, records and request helpers the task tests share. One customer with an invoice and a
 * payment on it covers all three kinds a task can be raised on, and {@link #otherSales} is a real
 * internal user who is nobody's POC here — the caller a test uses when it needs a record that
 * exists but is outside the caller's own book.
 */
abstract class TaskTestBase extends IntegrationTestBase {

    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected PocService pocService;
    @Autowired protected TaskService taskService;
    @Autowired protected PrivilegeRepository privilegeRepository;

    protected static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    protected static final LocalDate TOMORROW = TODAY.plusDays(1);
    protected static final LocalDate YESTERDAY = TODAY.minusDays(1);

    protected User admin;
    protected User sales;
    protected User otherSales;
    protected User collections;
    protected User success;
    protected Customer acme;
    protected User acmeLogin;
    protected Product widget;
    protected Invoice acmeInvoice;
    protected Payment acmePayment;

    @BeforeEach
    void taskFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        success = user("sue.success", DataSeeder.ROLE_SUCCESS_POC);
        acme = customer("Acme Ltd", "ap@acme.test");
        acmeLogin = customerUser("acme.login", acme.getId());
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
        acmeInvoice = invoice(acme, sales);
        acmePayment = payment(acme, "40.00");
    }

    // ---- records ---------------------------------------------------------------

    protected Invoice invoice(Customer customer, User salesPoc) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    protected Payment payment(Customer customer, String amount) {
        actAs(admin);
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(),
                new BigDecimal(amount), "Cash", null, null, collections.getId(), null));
    }

    // ---- request bodies --------------------------------------------------------

    /** A create body; extra key/value pairs override or add to the defaults. */
    protected static Map<String, Object> newTask(String entityType, Long entityId, Object... extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", entityType);
        body.put("entityId", entityId);
        body.put("title", "Chase the balance");
        for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
        return body;
    }

    protected static Map<String, Object> toUser(User u) {
        return Map.of("type", "USER", "userId", u.getId());
    }

    /** A role at one level: {@code CUSTOMER} for the customer's book, {@code RECORD} for the record's own POC. */
    protected static Map<String, Object> toRole(String role, String level) {
        return Map.of("type", "ROLE", "role", role, "level", level);
    }

    // ---- requests --------------------------------------------------------------

    protected ResultActions postTask(User caller, Object body) throws Exception {
        return mockMvc.perform(post("/api/tasks").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** Raises a task and hands back what the API said it is. */
    protected JsonNode raise(User caller, Object body) throws Exception {
        return read(postTask(caller, body).andExpect(status().isCreated()));
    }

    /**
     * PATCH bodies are written as raw JSON rather than built from a map, because which fields are
     * <em>present</em> and which are explicitly {@code null} is the whole point of these tests.
     */
    protected ResultActions patchTask(User caller, long id, String rawJson) throws Exception {
        return mockMvc.perform(patch("/api/tasks/" + id).with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(rawJson));
    }

    protected JsonNode edit(User caller, long id, String rawJson) throws Exception {
        return read(patchTask(caller, id, rawJson).andExpect(status().isOk()));
    }

    protected JsonNode getOk(String path, User caller, String... params) throws Exception {
        var request = get(path).with(as(caller));
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return read(mockMvc.perform(request).andExpect(status().isOk()));
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- reading the rows back -------------------------------------------------

    protected Task stored(long id) {
        return taskRepository.findById(id).orElseThrow();
    }

    protected static long idOf(JsonNode task) {
        return task.get("id").asLong();
    }

    /** The titles of a list response, in the order the page gave them. */
    protected static List<String> titles(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("content").forEach(row -> out.add(row.get("title").asText()));
        return out;
    }

    // ---- roles -----------------------------------------------------------------

    /**
     * A role holding exactly these privileges. Roles are not cleared between tests — only the
     * transactional tables are — so one is reused by name rather than created twice.
     */
    protected Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Test role: " + name)
                .privileges(privilegeSet(privileges))
                .build()));
    }

    private Set<Privilege> privilegeSet(String... names) {
        return Arrays.stream(names)
                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
