package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.AutomationWorker;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Rules written as the editor writes them, and the people and records the automation tests share. */
public abstract class AutomationTestBase extends IntegrationTestBase {

    protected static final String RULES = "/api/automation/rules";

    @Autowired protected PrivilegeRepository privilegeRepository;
    @Autowired protected InvoiceService invoiceService;

    @Autowired protected PaymentService paymentService;
    @Autowired protected AutomationWorker worker;

    /** Sells every invoice, and works every payment, the tests make; both hold the default region. */
    protected User seller;
    protected User collector;

    @BeforeEach
    void pocs() {
        seller = user("sol.seller", DataSeeder.ROLE_SALES_POC);
        collector = user("colm.collector", DataSeeder.ROLE_COLLECTION_POC);
    }

    /** A role with exactly these privileges; roles outlive a test, so it is set again each time. */
    protected Role roleWith(String name, String... privileges) {
        Role role = roleRepository.findByName(name)
                .orElseGet(() -> Role.builder().name(name).description(name).build());
        Set<Privilege> held = Arrays.stream(privileges)
                .map(p -> privilegeRepository.findByName(p).orElseThrow())
                .collect(Collectors.toCollection(HashSet::new));
        role.setPrivileges(held);
        return roleRepository.save(role);
    }

    /** A staff user holding exactly these grants: pairs of region and level, all with scope ALL unless a BOOK. */
    protected User staff(String username, String role, Object... grants) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        for (int i = 0; i < grants.length; i += 3) {
            grant(u, (Region) grants[i], (AccessLevel) grants[i + 1], (RecordScope) grants[i + 2]);
        }
        return u;
    }

    protected Long invoice(Customer c, Product product, int quantity, LocalDate due) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, due, null, null,
                seller.getId(), List.of(new InvoiceDtos.LineInput(product.getId(), quantity, null)))).getId();
    }

    protected Long pay(Customer c, String amount, Long invoiceId) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(), new BigDecimal(amount), "Cash",
                null, List.of(invoiceId), collector.getId(), null)).getId();
    }

    // ---- the consumer ---------------------------------------------------------------------------------------

    /** Drains the outbox as the scheduler would: on no one's behalf (R2), at the clock's now. */
    protected int drain() {
        SecurityContextHolder.clearContext();
        return worker.drain(MovableClock.SHARED.instant());
    }

    protected List<AutomationRun> runsOf(Long ruleId) {
        return automationRunRepository.findAll().stream().filter(r -> r.getRuleId().equals(ruleId))
                .sorted(Comparator.comparing(AutomationRun::getId)).toList();
    }

    protected List<Task> tasksOf(Long ruleId) {
        return taskRepository.findAll().stream().filter(t -> ruleId.equals(t.getRuleId()))
                .sorted(Comparator.comparing(Task::getId)).toList();
    }

    protected List<AutomationEvent> events(String kind) {
        return automationEventRepository.findAll().stream().filter(e -> e.getKind().equals(kind))
                .sorted(Comparator.comparing(AutomationEvent::getId)).toList();
    }

    protected AutomationEvent event(Long id) {
        return automationEventRepository.findById(id).orElseThrow();
    }

    // ---- rule bodies ----------------------------------------------------------------------------------------

    protected static Map<String, Object> rule(String name, String entity, String kind, Object condition,
                                              Object... actions) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("kind", kind);
        trigger.put("entityType", entity);
        if (kind.startsWith("SCHEDULE_")) trigger.put("time", "09:00");
        if (kind.equals("SCHEDULE_WEEKLY")) trigger.put("day", "MONDAY");
        trigger.put("zone", "UTC");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", null);
        body.put("enabled", true);
        body.put("trigger", trigger);
        body.put("regionIds", List.of());
        body.put("condition", condition);
        body.put("actions", List.of(actions));
        body.put("maxRecordsPerRun", 1000);
        body.put("version", null);
        body.put("confirmOwnershipChange", false);
        return body;
    }

    protected static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(key, value);
        return copy;
    }

    protected static Map<String, Object> leaf(String filter) {
        return Map.of("filter", filter);
    }

    protected static Map<String, Object> and(Object... children) {
        return Map.of("op", "AND", "children", List.of(children));
    }

    protected static Map<String, Object> or(Object... children) {
        return Map.of("op", "OR", "children", List.of(children));
    }

    protected static Map<String, Object> task(String title, Object... assignees) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", UUID.randomUUID().toString());
        a.put("type", "CREATE_TASK");
        a.put("repeat", "ONCE_PER_RECORD");
        a.put("title", title);
        a.put("due", Map.of("base", "TODAY", "offsetDays", 2));
        a.put("assignees", List.of(assignees));
        a.put("onlyIfNoOpenTask", true);
        return a;
    }

    /** The same task action, made again on every event, with no open-task guard. */
    protected static Map<String, Object> everyTime(Map<String, Object> action) {
        Map<String, Object> a = new LinkedHashMap<>(action);
        a.put("repeat", "EVERY_TIME");
        a.put("onlyIfNoOpenTask", false);
        return a;
    }

    protected static Map<String, Object> email(Object from, List<?> to, String subject, String body) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", UUID.randomUUID().toString());
        a.put("type", "SEND_EMAIL");
        a.put("repeat", "EVERY_TIME");
        a.put("from", from);
        a.put("fallbackToOwner", true);
        a.put("to", to);
        a.put("subject", subject);
        a.put("body", body);
        return a;
    }

    protected static Map<String, Object> userToken(User u) {
        return Map.of("type", "USER", "userId", u.getId());
    }

    protected static Map<String, Object> roleToken(String role, String level) {
        return Map.of("type", "ROLE", "role", role, "level", level);
    }

    // ---- requests -------------------------------------------------------------------------------------------

    protected ResultActions send(MockHttpServletRequestBuilder request, User caller, Object body) throws Exception {
        return mockMvc.perform(request.with(as(caller)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** A rule saved by {@code owner}, who becomes its owner; its id. */
    protected Long saveRule(User owner, Map<String, Object> body) throws Exception {
        ResultActions result = send(post(RULES), owner, body);
        JsonNode saved = body(result);
        if (result.andReturn().getResponse().getStatus() != 200) {
            throw new AssertionError("Rule not saved: " + saved);
        }
        return saved.get("id").asLong();
    }
}
