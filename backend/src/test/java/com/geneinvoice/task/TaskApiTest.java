package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.assignee.VisibilityProbe;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tasks over HTTP (A6, §6.12): made on a customer, an invoice or a payment in its customer's region, seen only by
 * those who see the record, changed by TASK_MANAGE in that region, moved along by its assignees, and kept, cancelled,
 * when the customer goes. Assignees are people who can open the record, or roles on it (§5.9.1).
 */
class TaskApiTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PocService pocService;
    @Autowired TaskService taskService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ApplicationContext context;
    @Autowired RunAs runAs;
    @Autowired TransactionTemplate tx;

    User admin;
    /** Collections, NORTH READ/ALL: sees every NORTH record, holds Acme's Collection seat, manages nothing. */
    User cara;
    /** Sales, NORTH READ/BOOK, sells nothing: sees no NORTH invoice. */
    User sam;
    /** Sales, NORTH READ/BOOK: sells the invoice. */
    User sid;
    /** A viewer, NORTH READ/ALL: TASK_VIEW but not TASK_MANAGE. */
    User vic;
    Region north;
    Region south;
    Product widget;
    Customer acme;
    Customer globex;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        cara = staff("cara.collections", DataSeeder.ROLE_COLLECTION_POC, north, AccessLevel.READ, RecordScope.ALL);
        sam = staff("sam.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.READ, RecordScope.BOOK);
        sid = staff("sid.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.READ, RecordScope.BOOK);
        vic = staff("vic.viewer", "VIEWER", north, AccessLevel.READ, RecordScope.ALL);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd", north);
        globex = customer("Globex Corp", south);
        actAs(admin);
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sid.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
    }

    /** A staff user holding exactly one grant. */
    private User staff(String username, String role, Region region, AccessLevel level, RecordScope scope) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        grant(u, region, level, scope);
        return u;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, User caller, Object body) throws Exception {
        return mockMvc.perform(request.with(as(caller)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(ResultActions result) throws Exception {
        return read(result.andExpect(status().isOk()));
    }

    private static Map<String, Object> user(User u) {
        return Map.of("type", "USER", "userId", u.getId());
    }

    private static Map<String, Object> role(String role, String level) {
        return Map.of("type", "ROLE", "role", role, "level", level);
    }

    private Map<String, Object> onInvoice(String title, Object... assignees) {
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "INVOICE");
        body.put("entityId", invoice.getId());
        body.put("title", title);
        body.put("assignees", List.of(assignees));
        return body;
    }

    private long create(User caller, Map<String, Object> body) throws Exception {
        return ok(send(post("/api/tasks"), caller, body)).get("id").asLong();
    }

    private JsonNode task(long id, User caller) throws Exception {
        return ok(mockMvc.perform(get("/api/tasks/" + id).with(as(caller))));
    }

    private ResultActions move(long id, User caller, String to, long version) throws Exception {
        return send(post("/api/tasks/" + id + "/status"), caller, Map.of("status", to, "version", version));
    }

    private List<String> actions(long taskId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("TASK", taskId).stream()
                .map(AuditLog::getAction).toList();
    }

    private List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).stream()
                .filter(n -> n.getType().equals(type)).toList();
    }

    /** Everything a refused write must leave as it was. */
    private Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tasks", jdbc.queryForList("select * from tasks order by id").toString());
        state.put("assignees", jdbc.queryForList("select * from record_assignees order by id").toString());
        state.put("audit", auditLogRepository.count());
        state.put("notifications", notificationRepository.count());
        return state;
    }

    @Test
    void aTaskIsMadeReadChangedAndCountedOnItsRecord() throws Exception {
        LocalDate due = LocalDate.now().plusDays(3);
        Map<String, Object> body = onInvoice("  Chase the invoice ", user(cara), role("COLLECTION_POC", "CUSTOMER"));
        body.put("description", "Call on Monday");
        body.put("dueDate", due.toString());

        JsonNode made = ok(send(post("/api/tasks"), admin, body));
        long id = made.get("id").asLong();
        assertThat(made.get("title").asText()).isEqualTo("Chase the invoice");
        assertThat(made.get("status").asText()).isEqualTo("OPEN");
        assertThat(made.get("dueDate").asText()).isEqualTo(due.toString());
        assertThat(made.get("overdue").asBoolean()).isFalse();
        assertThat(made.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(made.get("entityLabel").asText()).isEqualTo("Invoice " + invoice.getInvoiceNumber());
        assertThat(made.get("customerId").asLong()).isEqualTo(acme.getId());
        assertThat(made.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(made.get("regionCode").asText()).isEqualTo("NORTH");
        assertThat(made.get("origin").asText()).isEqualTo("USER");
        assertThat(made.at("/createdBy/id").asLong()).isEqualTo(admin.getId());
        assertThat(made.has("rule")).isFalse();
        assertThat(made.get("version").asLong()).isZero();
        assertThat(made.get("canEdit").asBoolean()).isTrue();
        assertThat(made.get("assignees")).hasSize(2);
        assertThat(made.at("/assignees/0/token/type").asText()).isEqualTo("USER");
        assertThat(made.at("/assignees/0/label").asText()).isEqualTo("CARA.COLLECTIONS");
        assertThat(made.at("/assignees/1/label").asText()).isEqualTo("Collection POC (customer)");
        assertThat(made.at("/assignees/1/people/0/userId").asLong()).isEqualTo(cara.getId());
        assertThat(made.at("/assignees/1/people/0/primary").asBoolean()).isTrue();
        assertThat(made.at("/assignees/1/unresolved").asBoolean()).isFalse();
        assertThat(taskRepository.findById(id).orElseThrow().getRegion().getId()).isEqualTo(north.getId());
        // Named and holding the role, cara is told once; admin made it and is not told at all.
        assertThat(noticesTo(cara, "TASK_ASSIGNED")).singleElement().satisfies(n -> {
            assertThat(n.getLink()).isEqualTo("/tasks/" + id);
            assertThat(n.getRegionId()).isEqualTo(north.getId());
            assertThat(n.getTitle()).isEqualTo("Task assigned: Chase the invoice");
        });
        assertThat(noticesTo(admin, "TASK_ASSIGNED")).isEmpty();

        assertThat(task(id, admin).get("description").asText()).isEqualTo("Call on Monday");
        JsonNode tab = ok(mockMvc.perform(get("/api/tasks").with(as(admin))
                .param("entityType", "INVOICE").param("entityId", invoice.getId().toString())));
        assertThat(tab.get("content")).extracting(t -> t.get("id").asLong()).containsExactly(id);
        assertThat(tab.get("appliedFilters")).extracting(JsonNode::asText)
                .contains("entityType:eq:INVOICE", "entityId:eq:" + invoice.getId());
        assertThat(tab.at("/content/0/assignees/1/label").asText()).isEqualTo("Collection POC (customer)");
        JsonNode count = ok(mockMvc.perform(get("/api/tasks/count").with(as(admin))
                .param("entityType", "INVOICE").param("entityId", invoice.getId().toString())));
        assertThat(count.get("count").asLong()).isEqualTo(1);
        assertThat(count.get("open").asLong()).isEqualTo(1);
        JsonNode customerTab = ok(mockMvc.perform(get("/api/tasks/summary").with(as(admin))
                .param("customerId", acme.getId().toString())));
        assertThat(customerTab.get("count").asLong()).as("the customer's tab counts its invoice's tasks").isEqualTo(1);
        assertThat(customerTab.get("open").asLong()).isEqualTo(1);

        JsonNode changed = ok(send(patch("/api/tasks/" + id), admin,
                Map.of("title", "Chase it again", "dueDate", due.plusDays(1).toString(), "version", 0)));
        assertThat(changed.get("title").asText()).isEqualTo("Chase it again");
        assertThat(changed.get("dueDate").asText()).isEqualTo(due.plusDays(1).toString());
        assertThat(changed.get("description").asText()).as("left alone when not sent").isEqualTo("Call on Monday");
        assertThat(changed.get("version").asLong()).isEqualTo(1);
        JsonNode cleared = ok(send(patch("/api/tasks/" + id), admin,
                Map.of("clearDueDate", true, "assignees", List.of(user(vic)), "version", 1)));
        assertThat(cleared.get("dueDate").isNull()).isTrue();
        assertThat(cleared.get("assignees")).extracting(a -> a.at("/token/userId").asLong())
                .containsExactly(vic.getId());
        assertThat(noticesTo(vic, "TASK_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(cara, "TASK_ASSIGNED")).as("not told again").hasSize(1);
        // The assignees removed are kept, closed, not deleted.
        assertThat(recordAssigneeRepository.findAll()).hasSize(3)
                .filteredOn(a -> a.getRemovedAt() == null).singleElement()
                .satisfies(a -> assertThat(a.getUserId()).isEqualTo(vic.getId()));

        assertThat(actions(id)).containsExactly("TASK_ASSIGNEES_CHANGED", "TASK_UPDATED", "TASK_UPDATED",
                "TASK_CREATED");
        JsonNode history = ok(mockMvc.perform(get("/api/audit").with(as(admin))
                .param("entityType", "TASK").param("entityId", String.valueOf(id))));
        assertThat(history).extracting(e -> e.get("action").asText()).contains("TASK_CREATED", "TASK_UPDATED");
        assertThat(history.get(0).get("entityLabel").asText()).isEqualTo("Chase it again");
        JsonNode related = ok(mockMvc.perform(get("/api/audit").with(as(admin)).param("entityType", "INVOICE")
                .param("entityId", invoice.getId().toString()).param("includeRelated", "true")));
        assertThat(related).extracting(e -> e.get("entityType").asText()).contains("TASK");
    }

    @Test
    void aTaskIsSeenOnlyWhereItsRecordIsSeen() throws Exception {
        long id = create(admin, onInvoice("Chase the invoice"));
        User stan = staff("stan.south", "CASHIER", south, AccessLevel.MANAGE, RecordScope.ALL);

        mockMvc.perform(get("/api/tasks/" + id).with(as(stan))).andExpect(status().isNotFound());
        assertThat(ok(mockMvc.perform(get("/api/tasks").with(as(stan)))).get("content")).isEmpty();
        Map<String, Object> before = state();
        send(patch("/api/tasks/" + id), stan, Map.of("title", "Mine now", "version", 0))
                .andExpect(status().isNotFound());
        move(id, stan, "DONE", 0).andExpect(status().isNotFound());
        assertThat(state()).isEqualTo(before);

        // sam holds NORTH but sells nothing there: the invoice, and so its task, is out of his book.
        mockMvc.perform(get("/api/tasks/" + id).with(as(sam))).andExpect(status().isNotFound());
        assertThat(ok(mockMvc.perform(get("/api/tasks").with(as(sam)))).get("content")).isEmpty();
        // sid sells it, so he sees it, though he may not change it.
        JsonNode seen = task(id, sid);
        assertThat(seen.get("canEdit").asBoolean()).isFalse();
        assertThat(seen.get("canChangeStatus").asBoolean()).isFalse();
    }

    /** TASK_MANAGE counts only where it is held at MANAGE: rita reads NORTH and manages SOUTH. */
    @Test
    void writesNeedTaskManageInTheTasksRegion() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL);
        grant(rita, south, AccessLevel.MANAGE, RecordScope.ALL);
        long northTask = create(admin, onInvoice("Chase the invoice"));
        Map<String, Object> before = state();

        send(post("/api/tasks"), rita, onInvoice("Mine")).andExpect(status().isForbidden());
        send(patch("/api/tasks/" + northTask), rita, Map.of("title", "Mine", "version", 0))
                .andExpect(status().isForbidden());
        move(northTask, rita, "CANCELLED", 0).andExpect(status().isForbidden());
        move(northTask, rita, "DONE", 0).andExpect(status().isForbidden());
        assertThat(state()).isEqualTo(before);

        JsonNode south = ok(send(post("/api/tasks"), rita,
                Map.of("entityType", "CUSTOMER", "entityId", globex.getId(), "title", "Welcome call")));
        assertThat(south.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(south.get("entityLabel").asText()).isEqualTo("Customer Globex Corp");
        assertThat(south.get("canEdit").asBoolean()).isTrue();
        JsonNode cancelled = ok(move(south.get("id").asLong(), rita, "CANCELLED", 0));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode reopened = ok(move(south.get("id").asLong(), rita, "OPEN", 1));
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void anAssigneeMayMoveATaskAlongButNeitherChangeNorCancelIt() throws Exception {
        long id = create(admin, onInvoice("Chase the invoice", user(vic)));
        User vera = staff("vera.viewer", "VIEWER", north, AccessLevel.READ, RecordScope.ALL);

        JsonNode seen = task(id, vic);
        assertThat(seen.get("assignedToMe").asBoolean()).isTrue();
        assertThat(seen.get("canEdit").asBoolean()).isFalse();
        assertThat(seen.get("canChangeStatus").asBoolean()).isTrue();
        assertThat(task(id, vera).get("canChangeStatus").asBoolean()).isFalse();
        move(id, vera, "DONE", 0).andExpect(status().isForbidden());

        assertThat(ok(move(id, vic, "IN_PROGRESS", 0)).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(ok(move(id, vic, "OPEN", 1)).get("status").asText()).isEqualTo("OPEN");
        JsonNode done = ok(move(id, vic, "DONE", 2));
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("completedAt").isNull()).isFalse();
        Task stored = taskRepository.findById(id).orElseThrow();
        assertThat(stored.getCompletedByUserId()).isEqualTo(vic.getId());
        // Made by admin by hand and finished by someone else: admin hears of it.
        assertThat(noticesTo(admin, "TASK_DONE")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/tasks/" + id));

        Map<String, Object> before = state();
        send(patch("/api/tasks/" + id), vic, Map.of("title", "Renamed", "version", 3))
                .andExpect(status().isForbidden());
        assertThat(state()).isEqualTo(before);

        JsonNode reopened = ok(move(id, vic, "OPEN", 3));
        assertThat(reopened.get("completedAt").isNull()).isTrue();
        before = state();
        move(id, vic, "CANCELLED", 4).andExpect(status().isForbidden());
        assertThat(state()).isEqualTo(before);
        ok(move(id, admin, "CANCELLED", 4));
        assertThat(task(id, vic).get("canChangeStatus").asBoolean())
                .as("only TASK_MANAGE brings back a cancelled task, so the flag does not offer it").isFalse();
        move(id, vic, "OPEN", 5).andExpect(status().isForbidden());
    }

    @Test
    void aMoveThatIsNoMoveOrThatTheStatusCannotMakeIsRefused() throws Exception {
        long id = create(admin, onInvoice("Chase the invoice"));
        JsonNode same = read(move(id, admin, "OPEN", 0).andExpect(status().isBadRequest()));
        assertThat(same.get("message").asText()).isEqualTo("The task is already open");
        ok(move(id, admin, "DONE", 0));
        JsonNode cancel = read(move(id, admin, "CANCELLED", 1).andExpect(status().isBadRequest()));
        assertThat(cancel.get("message").asText()).isEqualTo("A done task cannot become cancelled");
        send(post("/api/tasks/" + id + "/status"), admin, Map.of("status", "DONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() throws Exception {
        long id = create(admin, onInvoice("Chase the invoice"));
        ok(send(patch("/api/tasks/" + id), admin, Map.of("title", "First", "version", 0)));
        Map<String, Object> before = state();

        send(patch("/api/tasks/" + id), admin, Map.of("title", "Second", "version", 0))
                .andExpect(status().isConflict());
        move(id, admin, "DONE", 0).andExpect(status().isConflict());

        assertThat(state()).isEqualTo(before);
        assertThat(taskRepository.findById(id).orElseThrow().getTitle()).isEqualTo("First");
    }

    @Test
    void assigneesArePeopleOrRolesOfferedOnTheRecordEachOnceAndAtMostTen() throws Exception {
        User gone = user("gone.user", DataSeeder.ROLE_COLLECTION_POC);
        gone.setActive(false);
        userRepository.save(gone);
        User login = customerUser("acme.login", acme.getId());
        Map<String, Object> before = state();

        for (Object[] wrong : List.<Object[]>of(
                new Object[] {Map.of("type", "CUSTOMER"), "Assignees are people or roles"},
                new Object[] {role("SALES_POC", "RECORD"), "Sales POC (this customer) is not a role on customers"},
                new Object[] {user(gone), "gone.user is not an active internal user"},
                new Object[] {user(login), "acme.login is not an active internal user"},
                new Object[] {Map.of("type", "USER", "userId", 999999), "User #999999 does not exist"})) {
            JsonNode refused = read(send(post("/api/tasks"), admin, Map.of("entityType", "CUSTOMER",
                    "entityId", acme.getId(), "title", "Call", "assignees", List.of(wrong[0])))
                    .andExpect(status().isBadRequest()));
            assertThat(refused.at("/fieldErrors/assignees").asText()).isEqualTo(wrong[1]);
        }
        assertThat(state()).isEqualTo(before);
        List<Object> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(user(user("helper" + i, DataSeeder.ROLE_COLLECTION_POC)));
        Map<String, Object> afterUsers = state();
        JsonNode tooMany = read(send(post("/api/tasks"), admin, Map.of("entityType", "CUSTOMER",
                "entityId", acme.getId(), "title", "Call", "assignees", eleven)).andExpect(status().isBadRequest()));
        assertThat(tooMany.at("/fieldErrors/assignees").asText()).isEqualTo("At most 10 assignees");
        assertThat(state()).isEqualTo(afterUsers);

        // A role without a level takes its usual one; the same assignee twice is one assignee.
        JsonNode made = ok(send(post("/api/tasks"), admin, Map.of("entityType", "CUSTOMER", "entityId", acme.getId(),
                "title", "Call", "assignees", List.of(user(cara), user(cara),
                        Map.of("type", "ROLE", "role", "COLLECTION_POC")))));
        assertThat(made.get("assignees")).hasSize(2);
        assertThat(made.at("/assignees/1/token/level").asText()).isEqualTo("CUSTOMER");
    }

    /**
     * Someone named who could not open the record is refused, and the refusal is a plain 400 with nothing written:
     * the check never fails inside the transaction and leaves it to roll back at commit (CF-2).
     */
    @Test
    void aPersonWhoCannotSeeTheRecordIsRefusedAndNothingIsWritten() throws Exception {
        Map<String, Object> before = state();

        JsonNode refused = read(send(post("/api/tasks"), admin, onInvoice("Chase", user(cara), user(sam)))
                .andExpect(status().isBadRequest()));

        assertThat(refused.at("/fieldErrors/assignees").asText()).isEqualTo("SAM.SALES cannot see this record");
        assertThat(state()).isEqualTo(before);

        long id = create(admin, onInvoice("Chase", user(sid)));
        before = state();
        read(send(patch("/api/tasks/" + id), admin, Map.of("title", "Renamed", "assignees",
                List.of(user(sid), user(sam)), "version", 0)).andExpect(status().isBadRequest()));
        assertThat(state()).isEqualTo(before);
    }

    /**
     * A person named directly who sees the record but cannot open tasks in its region (no TASK_VIEW) would be stored,
     * never told and refused the task (CF-9): a 400 against assignees, on create and on change, and nothing written.
     * A rule's task leaves them out with a warning.
     */
    @Test
    void aPersonWhoCannotSeeTasksInTheRegionIsRefusedAsAnAssignee() throws Exception {
        Role noTasks = roleRepository.findByName("COLLECTIONS_NO_TASK_VIEW").orElseGet(() -> roleRepository.save(
                Role.builder().name("COLLECTIONS_NO_TASK_VIEW").description("no tasks")
                        .privileges(new HashSet<>(role(DataSeeder.ROLE_COLLECTION_POC).getPrivileges().stream()
                                .filter(p -> !p.getName().startsWith("TASK_")).toList())).build()));
        User nina = user("nina.notasks", noTasks.getName());
        grantRepository.deleteAllOfUser(nina.getId());
        grant(nina, north, AccessLevel.READ, RecordScope.ALL);
        mockMvc.perform(get("/api/invoices/" + invoice.getId()).with(as(nina))).andExpect(status().isOk());
        Map<String, Object> before = state();

        JsonNode refused = read(send(post("/api/tasks"), admin, onInvoice("Chase", user(cara), user(nina)))
                .andExpect(status().isBadRequest()));
        assertThat(refused.at("/fieldErrors/assignees").asText()).isEqualTo("NINA.NOTASKS cannot see tasks here");
        assertThat(state()).isEqualTo(before);

        long id = create(admin, onInvoice("Chase", user(cara)));
        before = state();
        JsonNode changed = read(send(patch("/api/tasks/" + id), admin, Map.of("assignees",
                List.of(user(cara), user(nina)), "version", 0)).andExpect(status().isBadRequest()));
        assertThat(changed.at("/fieldErrors/assignees").asText()).isEqualTo("NINA.NOTASKS cannot see tasks here");
        assertThat(state()).isEqualTo(before);

        SecurityContextHolder.clearContext();
        TaskService.TaskDraft draft = new TaskService.TaskDraft(TaskEntityType.INVOICE, invoice.getId(), "Chase",
                null, null, List.of(EmailToken.user(cara.getId()), EmailToken.user(nina.getId())), 55L, 7L, false);
        TaskService.Created created = runAs.asUser(admin.getId(), ViewScope.LIVE_ALL,
                WriteOrigin.automation(55L, 7L, null, 1), () -> tx.execute(st -> taskService.createForAutomation(draft)));
        assertThat(created.warnings()).containsExactly("NINA.NOTASKS cannot see tasks here, so was not assigned");
        assertThat(recordAssigneeRepository.findAll()).filteredOn(a -> a.getOwnerId().equals(created.task().getId()))
                .extracting(a -> a.getUserId()).containsExactly(cara.getId());
    }

    /**
     * A role's holder who cannot open tasks (no TASK_VIEW) is not one of its assignees (PC-2): not told of it, not
     * listed among the people it means, and not allowed to move it along.
     */
    @Test
    void aRoleHolderWithoutTaskViewIsNeitherToldNorAnAssignee() throws Exception {
        Role noTasks = roleRepository.save(Role.builder().name("COLLECTIONS_NO_TASKS").description("no tasks")
                .privileges(new HashSet<>(role(DataSeeder.ROLE_COLLECTION_POC).getPrivileges().stream()
                        .filter(p -> !p.getName().startsWith("TASK_")).toList())).build());
        User nick = user("nick.collections", noTasks.getName());
        grantRepository.deleteAllOfUser(nick.getId());
        grant(nick, north, AccessLevel.READ, RecordScope.ALL);
        pocService.add(acme.getId(), PocType.COLLECTION, nick.getId(), false);

        long id = create(admin, onInvoice("Chase", role("COLLECTION_POC", "CUSTOMER")));

        assertThat(noticesTo(cara, "TASK_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(nick, "TASK_ASSIGNED")).isEmpty();
        // Nor does the invoice's trail show him the task (he can read the trail, not tasks).
        JsonNode trail = ok(mockMvc.perform(get("/api/audit").with(as(nick)).param("entityType", "INVOICE")
                .param("entityId", invoice.getId().toString()).param("includeRelated", "true")));
        assertThat(trail).isNotEmpty().extracting(e -> e.get("entityType").asText()).doesNotContain("TASK");
        assertThat(ok(mockMvc.perform(get("/api/audit").with(as(cara)).param("entityType", "INVOICE")
                .param("entityId", invoice.getId().toString()).param("includeRelated", "true"))))
                .extracting(e -> e.get("entityType").asText()).contains("TASK");
        assertThat(task(id, admin).at("/assignees/0/people")).extracting(p -> p.get("userId").asLong())
                .containsExactly(cara.getId());
        move(id, nick, "DONE", 0).andExpect(status().isForbidden());
        actAs(nick);
        assertThatThrownBy(() -> taskService.changeStatus(id, TaskStatus.DONE, 0L))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(ok(move(id, cara, "DONE", 0)).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void deletingTheCustomerCancelsItsOpenTasksAndMarksThemAll() throws Exception {
        Customer tiny = customer("Tiny Co", north);
        long open = create(admin, Map.of("entityType", "CUSTOMER", "entityId", tiny.getId(), "title", "Call",
                "assignees", List.of(role("COLLECTION_POC", "CUSTOMER"))));
        long done = create(admin, Map.of("entityType", "CUSTOMER", "entityId", tiny.getId(), "title", "Visit"));
        ok(move(done, admin, "DONE", 0));

        mockMvc.perform(delete("/api/customers/" + tiny.getId()).with(as(admin))).andExpect(status().isOk());

        Task cancelled = taskRepository.findById(open).orElseThrow();
        assertThat(cancelled.isEntityDeleted()).isTrue();
        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        Task kept = taskRepository.findById(done).orElseThrow();
        assertThat(kept.isEntityDeleted()).isTrue();
        assertThat(kept.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(actions(open)).first().isEqualTo("TASK_CANCELLED_WITH_RECORD");
        assertThat(actions(done)).first().isEqualTo("TASK_CANCELLED_WITH_RECORD");

        JsonNode seen = task(open, admin);
        assertThat(seen.get("entityDeleted").asBoolean()).isTrue();
        assertThat(seen.get("entityLabel").asText()).isEqualTo("Customer Tiny Co");
        assertThat(seen.at("/assignees/0/unresolved").asBoolean()).isTrue();
        assertThat(ok(mockMvc.perform(get("/api/tasks").with(as(admin))
                .param("customerId", tiny.getId().toString()))).get("totalElements").asLong()).isEqualTo(2);

        // Closed with its record, and left so: not reopened, not changed, and no flag offers either.
        assertThat(seen.get("canEdit").asBoolean()).isFalse();
        assertThat(seen.get("canChangeStatus").asBoolean()).isFalse();
        long version = seen.get("version").asLong();
        Map<String, Object> before = state();
        JsonNode reopen = read(move(open, admin, "OPEN", version).andExpect(status().isBadRequest()));
        assertThat(reopen.get("message").asText()).isEqualTo("The record this task was on has been deleted");
        send(patch("/api/tasks/" + open), admin, Map.of("title", "Call again", "version", version))
                .andExpect(status().isBadRequest());
        send(patch("/api/tasks/" + done), admin, Map.of("assignees", List.of(user(cara)), "version",
                task(done, admin).get("version").asLong())).andExpect(status().isBadRequest());
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void someoneStillAssignedIsDeactivatedRatherThanDeleted() throws Exception {
        User helper = staff("helper", "VIEWER", north, AccessLevel.READ, RecordScope.ALL);
        long id = create(admin, onInvoice("Chase", user(helper)));

        JsonNode kept = ok(mockMvc.perform(delete("/api/users/" + helper.getId()).with(as(admin))));
        assertThat(kept.get("deactivated").asBoolean()).isTrue();
        assertThat(kept.get("pocReferences").asLong()).isEqualTo(1);
        assertThat(userRepository.findById(helper.getId())).isPresent();

        User again = userRepository.findById(helper.getId()).orElseThrow();
        again.setActive(true);
        userRepository.save(again);
        ok(send(patch("/api/tasks/" + id), admin, Map.of("assignees", List.of(), "version", 0)));
        JsonNode deleted = ok(mockMvc.perform(delete("/api/users/" + helper.getId()).with(as(admin))));
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
    }

    @Test
    void bulkActionsMoveEveryTaskTheCallerManagesAndSkipTheRest() throws Exception {
        long a = create(admin, onInvoice("A"));
        long b = create(admin, onInvoice("B"));
        long c = create(admin, onInvoice("C"));
        ok(move(c, admin, "DONE", 0));
        long south = create(admin, Map.of("entityType", "CUSTOMER", "entityId", globex.getId(), "title", "S"));

        JsonNode result = ok(send(post("/api/tasks/bulk"), admin,
                Map.of("action", "MARK_DONE", "ids", List.of(a, b, c))));
        assertThat(result.get("succeeded")).extracting(JsonNode::asLong).containsExactlyInAnyOrder(a, b);
        assertThat(result.at("/skipped/0/id").asLong()).isEqualTo(c);
        assertThat(result.at("/skipped/0/reason").asText()).isEqualTo("The task is already done");
        assertThat(taskRepository.findById(a).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);

        User stan = staff("stan.south", "CASHIER", this.south, AccessLevel.MANAGE, RecordScope.ALL);
        JsonNode stans = ok(send(post("/api/tasks/bulk"), stan,
                Map.of("action", "START", "ids", List.of(a, south))));
        assertThat(stans.get("succeeded")).extracting(JsonNode::asLong).containsExactly(south);
        assertThat(stans.at("/skipped/0/id").asLong()).isEqualTo(a);
        send(post("/api/tasks/bulk"), admin, Map.of("action", "ARCHIVE", "ids", List.of(a)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListExportsAsCsv() throws Exception {
        create(admin, onInvoice("Chase the invoice", user(cara)));
        create(admin, Map.of("entityType", "CUSTOMER", "entityId", globex.getId(), "title", "Welcome call"));

        String text = mockMvc.perform(post("/api/tasks/export").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("selectAllMatchingFilter", true,
                                "filters", List.of("regionId:eq:" + north.getId())))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("Id,Title,Status,Due,On,Record,Customer,Assigned to,Region,Created,Completed")
                .contains("Chase the invoice", "Acme Ltd", "CARA.COLLECTIONS", "NORTH")
                .doesNotContain("Welcome call");
        mockMvc.perform(post("/api/tasks/export").with(as(vic)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("selectAllMatchingFilter", true)))).andExpect(status().isForbidden());
    }

    /**
     * The task's region is read from the customer row it locks, after the read-only work: a move that commits while
     * the task waits for the lock puts the task in the new region, never the old one (F1).
     */
    @Test
    void theRegionIsTheOneOnTheCustomerRowTheTaskLocked() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try (Connection holder = session()) {
            try (PreparedStatement st = holder.prepareStatement("update customers set region_id = ? where id = ?")) {
                st.setLong(1, south.getId());
                st.setLong(2, acme.getId());
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            Future<ResultActions> running = writer.submit(() -> send(post("/api/tasks"), admin,
                    Map.of("entityType", "CUSTOMER", "entityId", acme.getId(), "title", "Call")));
            awaitWaitingOnALock(running);
            holder.commit();
            JsonNode made = ok(running.get(10, TimeUnit.SECONDS));
            assertThat(made.get("regionCode").asText()).isEqualTo("SOUTH");
            assertThat(taskRepository.findById(made.get("id").asLong()).orElseThrow().getRegion().getId())
                    .isEqualTo(south.getId());
        } finally {
            writer.shutdownNow();
            // The row was moved by hand: put the customer back and its history in step.
            jdbc.update("update customers set region_id = ? where id = ?", north.getId(), acme.getId());
            jdbc.update("update tasks set region_id = ? where customer_id = ?", north.getId(), acme.getId());
            historyTestSupport.touch(Customer.class, List.of(acme.getId()));
            historyTestSupport.touch(Task.class, taskRepository.findAll().stream().map(Task::getId).toList());
        }
    }

    private Connection session() throws SQLException {
        Connection c = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"), "");
        c.setAutoCommit(false);
        return c;
    }

    private void awaitWaitingOnALock(Future<?> running) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) {
                running.get();
                fail("the task was made without waiting for its customer's lock");
            }
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("the task never waited for its customer's lock");
    }

    /**
     * A rule's task, made as its owner inside the action's transaction: made by AUTOMATION under the rule, whoever
     * cannot see the record left out with a warning, and the owner told of what the rule gave them. RunAs puts the
     * caller's identity and provenance back afterwards.
     */
    @Test
    void aRulesTaskIsMadeAsItsOwnerAndLeavesOutWhoeverCannotSeeTheRecord() throws Exception {
        SecurityContextHolder.clearContext();
        TaskService.TaskDraft draft = new TaskService.TaskDraft(TaskEntityType.INVOICE, invoice.getId(),
                "Chase " + invoice.getInvoiceNumber(), "Overdue", null,
                List.of(EmailToken.user(admin.getId()), EmailToken.user(sam.getId())), 55L, 7L, false);

        TaskService.Created created = runAs.asUser(admin.getId(), ViewScope.LIVE_ALL,
                WriteOrigin.automation(55L, 7L, null, 1), () -> tx.execute(status -> taskService.createForAutomation(draft)));

        Task task = taskRepository.findById(created.task().getId()).orElseThrow();
        assertThat(task.getOrigin()).isEqualTo("AUTOMATION");
        assertThat(task.getRuleId()).isEqualTo(55L);
        assertThat(task.getRunId()).isEqualTo(7L);
        assertThat(task.getCreatedByUserId()).isEqualTo(admin.getId());
        assertThat(created.warnings()).containsExactly("SAM.SALES cannot see this record, so was not assigned");
        assertThat(recordAssigneeRepository.findAll()).extracting(a -> a.getUserId()).containsExactly(admin.getId());
        assertThat(noticesTo(admin, "TASK_ASSIGNED")).as("the owner hears of what the rule gave them").hasSize(1);
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("TASK", task.getId()))
                .singleElement().satisfies(a -> {
                    assertThat(a.getOrigin()).isEqualTo("AUTOMATION");
                    assertThat(a.getRuleId()).isEqualTo(55L);
                    assertThat(a.getChangedByUserId()).isEqualTo(admin.getId());
                });
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(WriteOrigins.current(null).kind()).isEqualTo(WriteOrigin.Kind.SYSTEM);

        // A person's own change to the rule's task is theirs: they are not told of assigning themselves.
        long version = ok(send(patch("/api/tasks/" + task.getId()), admin,
                Map.of("assignees", List.of(), "version", task.getVersion()))).get("version").asLong();
        ok(send(patch("/api/tasks/" + task.getId()), admin, Map.of("assignees", List.of(user(admin), user(cara)),
                "version", version)));
        assertThat(noticesTo(admin, "TASK_ASSIGNED")).as("told once, of what the rule gave them").hasSize(1);
        assertThat(noticesTo(cara, "TASK_ASSIGNED")).hasSize(1);

        actAs(admin);
        assertThatThrownBy(() -> taskService.createForAutomation(draft))
                .isInstanceOf(IllegalTransactionStateException.class);
        User gone = user("gone", "CASHIER");
        gone.setActive(false);
        userRepository.save(gone);
        assertThatThrownBy(() -> runAs.asUser(gone.getId(), ViewScope.LIVE_ALL, WriteOrigin.system(), () -> 1))
                .isInstanceOf(RunAs.InactiveUserException.class);
    }

    /**
     * The domain services keep assignees through a repository-only store, so nothing they inject can reach the email
     * targets, which inject them (CF-3): the context starts, and the store and the cascade name no service.
     */
    @Test
    void theContextStartsWithAssigneesKeptApartFromTheServicesThatUseThem() {
        assertThat(context.getBean(TaskService.class)).isNotNull();
        assertThat(context.getBean(AssigneeService.class)).isNotNull();
        assertThat(context.getBean(VisibilityProbe.class)).isNotNull();
        assertThat(context.getBean(TaskMoveParticipant.class)).isNotNull();
        for (Class<?> repositoryOnly : List.of(AssigneeStore.class, TaskCascade.class)) {
            Set<String> injected = new HashSet<>();
            for (Constructor<?> c : repositoryOnly.getDeclaredConstructors()) {
                Arrays.stream(c.getParameterTypes()).map(Class::getSimpleName).forEach(injected::add);
            }
            assertThat(injected).as(repositoryOnly.getSimpleName())
                    .noneMatch(name -> name.endsWith("Service") && !name.equals("AuditService"))
                    .doesNotContain(EmailTargets.class.getSimpleName());
        }
        assertThat(Privileges.ALL).contains(Privileges.TASK_VIEW, Privileges.TASK_MANAGE);
        assertThat(role("CASHIER").getPrivileges()).extracting(Privilege::getName)
                .contains(Privileges.TASK_VIEW, Privileges.TASK_MANAGE);
        assertThat(role("VIEWER").getPrivileges()).extracting(Privilege::getName)
                .contains(Privileges.TASK_VIEW).doesNotContain(Privileges.TASK_MANAGE);
        assertThat(role("CUSTOMER").getPrivileges()).extracting(Privilege::getName)
                .doesNotContain(Privileges.TASK_VIEW, Privileges.TASK_MANAGE);
    }
}
