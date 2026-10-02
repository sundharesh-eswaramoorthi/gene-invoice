package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "My tasks" (§5.9.5): the tasks I can see that name me, or a role I hold on their record right now (a seat on the
 * customer, or the record's own POC). It is a view, not my book: nothing is locked (R14), and a task on a record I
 * cannot see is never mine to see.
 */
class MyTasksTest extends IntegrationTestBase {

    @Autowired TaskService taskService;
    @Autowired InvoiceService invoiceService;
    @Autowired PocService pocService;

    User admin;
    /** Collections, NORTH READ/ALL: sees every NORTH record. */
    User cara;
    /** Sales, NORTH READ/BOOK: sells the invoice. */
    User sid;
    Region north;
    Product widget;
    Customer acme;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        cara = staff("cara.collections", DataSeeder.ROLE_COLLECTION_POC, AccessLevel.READ, RecordScope.ALL);
        sid = staff("sid.sales", DataSeeder.ROLE_SALES_POC, AccessLevel.READ, RecordScope.BOOK);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd", north);
        actAs(admin);
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sid.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private User staff(String username, String role, AccessLevel level, RecordScope scope) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        grant(u, north, level, scope);
        return u;
    }

    private Task task(TaskEntityType type, Long id, String title, LocalDate due, EmailToken... assignees) {
        actAs(admin);
        return taskService.create(new TaskDtos.CreateTaskRequest(type, id, title, null, due, List.of(assignees)));
    }

    private JsonNode read(String url, User caller) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).with(as(caller)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Long> mine(User caller) throws Exception {
        return ids(read("/api/tasks/mine", caller));
    }

    /** The task ids on a page, in its order. */
    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new java.util.ArrayList<>();
        page.get("content").forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    /** A customer-level role means whoever holds that seat now: taking a seat brings the task, giving it up takes it. */
    @Test
    void aSeatTakenAfterTheTaskWasMadeBringsItAndGivingItUpTakesItAway() throws Exception {
        Task t = task(TaskEntityType.CUSTOMER, acme.getId(), "Call them", null,
                EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC)));
        assertThat(mine(cara)).isEmpty();

        actAs(admin);
        Long seat = pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true).getId();
        assertThat(mine(cara)).containsExactly(t.getId());
        JsonNode detail = read("/api/tasks/" + t.getId(), cara);
        assertThat(detail.get("assignedToMe").asBoolean()).isTrue();
        assertThat(detail.at("/assignees/0/people/0/userId").asLong()).isEqualTo(cara.getId());

        actAs(admin);
        pocService.remove(acme.getId(), seat);
        assertThat(mine(cara)).isEmpty();
        assertThat(read("/api/tasks/" + t.getId(), cara).get("assignedToMe").asBoolean()).isFalse();
    }

    @Test
    void theInvoicesOwnSalesPocHasTheTaskAssignedToThatRole() throws Exception {
        Task t = task(TaskEntityType.INVOICE, invoice.getId(), "Chase", null,
                EmailToken.role(RoleRef.record(EmailRole.SALES_POC)));
        Task other = task(TaskEntityType.CUSTOMER, acme.getId(), "Call", null,
                EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC)));

        assertThat(mine(sid)).containsExactly(t.getId());
        assertThat(mine(admin)).isEmpty();
        assertThat(ids(read("/api/tasks", admin)))
                .contains(t.getId(), other.getId());
        // The same people through the list's column.
        JsonNode filtered = objectMapper.readTree(mockMvc.perform(
                        get("/api/tasks")
                                .with(as(admin)).param("filter", "assigneeUserId:eq:" + sid.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(ids(filtered)).containsExactly(t.getId());
    }

    /**
     * colin holds Acme's Collection seat but, reading NORTH as his book only, sees no invoice: the invoice's task that
     * names his role is not his to see, in the list or the count.
     */
    @Test
    void aTaskOnARecordICannotSeeIsNeverListedAsMine() throws Exception {
        User colin = staff("colin.collections", DataSeeder.ROLE_COLLECTION_POC, AccessLevel.READ, RecordScope.BOOK);
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), true);
        Task onInvoice = task(TaskEntityType.INVOICE, invoice.getId(), "Chase", null,
                EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC)));
        Task onCustomer = task(TaskEntityType.CUSTOMER, acme.getId(), "Call", null,
                EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC)));

        assertThat(mine(colin)).containsExactly(onCustomer.getId());
        assertThat(read("/api/tasks/mine/count", colin).get("count").asLong()).isEqualTo(1);
        assertThat(read("/api/tasks/mine/summary", colin).get("count").asLong()).isEqualTo(1);
        mockMvc.perform(get("/api/tasks/" + onInvoice.getId()).with(as(colin))).andExpect(status().isNotFound());
        // Nor is he told of it, or shown as one of the people its role means (PC-2): only of the customer's task.
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(colin.getId()))
                .filteredOn(n -> n.getType().equals("TASK_ASSIGNED")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/tasks/" + onCustomer.getId()));
        JsonNode seenByAdmin = read("/api/tasks/" + onInvoice.getId(), admin);
        assertThat(seenByAdmin.at("/assignees/0/people")).isEmpty();
        assertThat(seenByAdmin.at("/assignees/0/unresolved").asBoolean()).isTrue();
        assertThat(read("/api/tasks/" + onCustomer.getId(), admin).at("/assignees/0/people"))
                .extracting(p -> p.get("userId").asLong()).containsExactly(colin.getId());
        // His customer's trail brings in only the task he could open.
        JsonNode trail = objectMapper.readTree(mockMvc.perform(get("/api/audit").with(as(colin))
                        .param("entityType", "CUSTOMER").param("entityId", acme.getId().toString())
                        .param("includeRelated", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(trail).filteredOn(e -> e.get("entityType").asText().equals("TASK"))
                .extracting(e -> e.get("entityId").asLong()).isNotEmpty().containsOnly(onCustomer.getId());
    }

    @Test
    void myCountIsWhatIsStillToDoAndMySummaryHasTheListsShapeWithNothingLocked() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        EmailToken me = EmailToken.user(cara.getId());
        Task overdue = task(TaskEntityType.CUSTOMER, acme.getId(), "Late", today.minusDays(2), me);
        Task dueToday = task(TaskEntityType.CUSTOMER, acme.getId(), "Today", today, me);
        Task started = task(TaskEntityType.INVOICE, invoice.getId(), "Started", null, me);
        Task finished = task(TaskEntityType.INVOICE, invoice.getId(), "Finished", null, me);
        task(TaskEntityType.CUSTOMER, acme.getId(), "Not mine", today.minusDays(2));
        actAs(cara);
        taskService.changeStatus(started.getId(), TaskStatus.IN_PROGRESS, 0L);
        taskService.changeStatus(finished.getId(), TaskStatus.DONE, 0L);

        assertThat(read("/api/tasks/mine/count", cara).get("count").asLong()).isEqualTo(3);
        JsonNode summary = read("/api/tasks/mine/summary", cara);
        assertThat(summary.get("count").asLong()).isEqualTo(4);
        assertThat(summary.get("open").asLong()).isEqualTo(2);
        assertThat(summary.get("inProgress").asLong()).isEqualTo(1);
        assertThat(summary.get("overdue").asLong()).isEqualTo(1);
        assertThat(summary.get("dueToday").asLong()).isEqualTo(1);
        assertThat(read("/api/tasks/summary", admin).get("count").asLong()).isEqualTo(5);

        JsonNode page = read("/api/tasks/mine", cara);
        assertThat(page.get("lockedFilters")).as("mine is a view, not a locked book (R14)").isEmpty();
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
        assertThat(ids(page))
                .containsExactlyInAnyOrder(overdue.getId(), dueToday.getId(), finished.getId(), started.getId());
        assertThat(page.get("content")).filteredOn(t -> t.get("overdue").asBoolean())
                .extracting(t -> t.get("id").asLong()).containsExactly(overdue.getId());
        assertThat(page.get("content")).allSatisfy(t -> assertThat(t.get("assignedToMe").asBoolean()).isTrue());
        assertThat(read("/api/tasks", cara).get("lockedFilters")).isEmpty();
    }
}
