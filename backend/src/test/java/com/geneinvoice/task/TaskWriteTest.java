package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAxes;
import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five writes, and the two things about them that are security properties rather than
 * plumbing: a task cannot be made about a record the caller cannot see (404, the READ gate), and
 * it cannot be made in a branch the caller cannot manage (403, the WRITE gate, because the branch
 * was NAMED by naming a record in it) (A6, B1, AUTH-08, D-46).
 */
class TaskWriteTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired CustomerRepository customers;
    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired TaskService taskService;
    @Autowired TaskCascade taskCascade;
    @Autowired AuditLogRepository auditLogRepository;

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    User admin;
    User hq;
    User mate;
    User checker;
    Region north;
    Customer home;
    Customer away;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        hq = user("hank.hq", taskRole().getName());
        mate = user("mona.mate", taskRole().getName());
        checker = user("carl.checker", checkerRole().getName());
        widget = product("Widget", "100.00");
        home = customer("Home Ltd");
        away = customers.save(Customer.builder().name("Away Ltd").region(north).build());
        seat(home, hq);
    }

    @Test
    void aTaskCannotBeMadeAboutARecordTheCallerCannotSee() throws Exception {
        actAs(admin);
        Invoice awayInvoice = invoiceFor(away);

        // Hank has no seat on the northern account and no grant in NORTH, so the READ gate inside
        // customerService.get / invoiceService.get answers first, and it answers 404 — the refusal
        // must not become a way of asking which ids exist up there (AUTH-08).
        mockMvc.perform(post("/api/tasks").with(as(hq)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                                away.getId(), "Not mine", null, null, List.of()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tasks").with(as(hq)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskDtos.CreateTaskRequest(TaskEntityType.INVOICE,
                                awayInvoice.getId(), "Nor this", null, null, List.of()))))
                .andExpect(status().isNotFound());
        assertThat(taskRepository.findAll()).isEmpty();

        // The account he does work on goes through, and the label is snapshotted from the record
        // rather than sent by the caller (A6).
        JsonNode made = read(mockMvc.perform(post("/api/tasks").with(as(hq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                                home.getId(), "Chase the PO", null, TODAY.plusDays(2), List.of()))))
                .andExpect(status().isOk()));
        assertThat(made.get("entityLabel").asText()).isEqualTo("Customer Home Ltd");
        assertThat(made.get("customerId").asLong()).isEqualTo(home.getId());
        assertThat(made.get("regionId").asLong()).isEqualTo(defaultRegion().getId());

        // Now the other half, which 404 cannot tell apart on its own: somebody who CAN see the
        // northern account but may only look at it. Naming a region on a write is 403, because no
        // id space is being probed — the caller already knows the record is there (B1, D-46).
        User nate = user("nate.north", taskRole().getName());
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(nate.getId()).regionId(north.getId()).right(RegionRight.VIEW).build());
        seat(away, nate);
        mockMvc.perform(post("/api/tasks").with(as(nate)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                                away.getId(), "Look but do not touch", null, null, List.of()))))
                .andExpect(status().isForbidden());
        assertThat(taskRepository.findByCustomerId(away.getId())).isEmpty();
    }

    @Test
    void assigningSomebodyNotifiesThemAndAssigningMyselfDoesNot() throws Exception {
        actAs(hq);
        TaskDtos.TaskDto made = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Share the work", null, null,
                List.of(hq.getId(), mate.getId())));

        assertThat(assigned()).extracting(Notification::getUserId).containsExactly(mate.getId());
        assertThat(notificationRepository.findAll()).extracting(Notification::getLink)
                .contains("/tasks/" + made.id());

        // Saving the same seats again tells nobody again: a notification list that repeats itself
        // is one people stop reading (A6).
        taskService.update(made.id(), new TaskDtos.UpdateTaskRequest(
                "Share the work, revised", null, null, null, List.of(hq.getId(), mate.getId())));
        assertThat(assigned()).hasSize(1);

        // A person who was NOT already on it is told, once.
        User third = user("tina.third", taskRole().getName());
        taskService.update(made.id(), new TaskDtos.UpdateTaskRequest(
                null, null, null, null, List.of(hq.getId(), mate.getId(), third.getId())));
        assertThat(assigned()).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(mate.getId(), third.getId());
    }

    @Test
    void thesameUserCannotBeAddedToOneTaskTwice() {
        actAs(hq);
        TaskDtos.TaskDto made = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Once only", null, null,
                List.of(mate.getId(), mate.getId())));

        // The service de-duplicates before anything is written, so a repeated id is the quiet
        // "they are already on it" and not a 500 out of the flush (A6).
        assertThat(made.assignees()).extracting(TaskDtos.AssigneeDto::userId)
                .containsExactly(mate.getId());

        TaskDtos.TaskDto again = taskService.update(made.id(), new TaskDtos.UpdateTaskRequest(
                null, null, null, null, List.of(mate.getId(), hq.getId(), mate.getId())));
        assertThat(again.assignees()).extracting(TaskDtos.AssigneeDto::userId)
                .containsExactly(mate.getId(), hq.getId());

        // And the database says the same thing, so the rule survives a caller that does not go
        // through the service at all — uk_task_assignee, on both dialects (A6).
        Task row = taskRepository.findById(made.id()).orElseThrow();
        assertThatThrownBy(() -> taskAssigneeRepository.saveAndFlush(TaskAssignee.builder()
                .task(row).userId(mate.getId()).source(TaskAssignee.SOURCE_USER).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completingATaskRecordsWhoDidItAndWhen() throws Exception {
        actAs(admin);
        TaskDtos.TaskDto made = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Finish me", null, TODAY.minusDays(1),
                List.of(hq.getId())));
        assertThat(made.overdue()).isTrue();

        JsonNode done = read(mockMvc.perform(post("/api/tasks/" + made.id() + "/complete")
                        .with(as(hq)))
                .andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("completedByUserId").asLong()).isEqualTo(hq.getId());
        assertThat(done.get("completedAt").isNull()).isFalse();
        // A finished task is not overdue, however late it was: the derived column reads the
        // status as well as the clock (A6, D3).
        assertThat(done.get("overdue").asBoolean()).isFalse();

        // THE ORDER IS THE ASSERTION, so the QUERY establishes it. findAll() carries no ORDER BY
        // at all, so what comes back is whatever order the database chose to scan its own table
        // in: H2 hands back insertion order and Postgres hands back physical order, which is the
        // same thing only while the table is small and untouched. Against Postgres with a whole
        // run's worth of audit rows behind it — and rows that had just changed size, because the
        // @Lob fix moved every blob out of pg_largeobject and into the row — these two came back
        // reversed and the test read as "completion is recorded before creation": a failure with
        // nothing wrong behind it.
        //
        // createdAt THEN id, and not containsExactlyInAnyOrder, because relaxing it would delete
        // the very thing this test is for. createdAt is what the claim actually means — the
        // creation was recorded BEFORE the completion — and id breaks a tie in the one instant
        // both stamps could be equal, so the pair is both meaningful and total. It asserts
        // strictly more than the unordered read ever did (A6).
        assertThat(auditLogRepository.findAll(Sort.by("createdAt", "id"))).filteredOn(
                        a -> TaskService.ENTITY.equals(a.getEntityType())
                                && a.getEntityId().equals(made.id()))
                .extracting(AuditLog::getAction)
                .containsExactly("TASK_CREATED", "TASK_COMPLETED");
        assertThat(auditLogRepository.findAll()).filteredOn(
                        a -> "TASK_COMPLETED".equals(a.getAction()))
                .allMatch(a -> a.getChangedByUserId().equals(hq.getId()));

        // Twice is a row that did not qualify, said in the filter bar's own words, and it is a
        // BadRequestException so BulkExecutor.eligibility can turn it into "skipped" (A6, TBL-05).
        mockMvc.perform(post("/api/tasks/" + made.id() + "/complete").with(as(hq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This task is already done"));

        // Reopening un-finishes it rather than leaving a stamp saying somebody completed a task
        // that is open again (A6).
        JsonNode reopened = read(mockMvc.perform(patch("/api/tasks/" + made.id()).with(as(hq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk()));
        assertThat(reopened.get("completedAt").isNull()).isTrue();
        assertThat(reopened.get("completedByUserId").isNull()).isTrue();
    }

    @Test
    void aBulkCompleteReportsTheRowsItCouldNotReachRatherThanFailing() throws Exception {
        actAs(admin);
        Long mine = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                home.getId(), "Reachable and open", null, null, List.of())).id();
        Long already = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                home.getId(), "Already finished", null, null, List.of())).id();
        Long elsewhere = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                away.getId(), "Another branch", null, null, List.of())).id();
        taskService.complete(already);

        JsonNode result = read(mockMvc.perform(post("/api/tasks/bulk").with(as(hq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"COMPLETE\",\"ids\":[" + mine + "," + already + ","
                                + elsewhere + "]}"))
                // 200 and not 500: a run that cannot act on some of its rows reports them (TBL-05).
                .andExpect(status().isOk()));

        assertThat(longs(result.get("succeeded"))).containsExactly(mine);
        assertThat(result.get("failed")).isEmpty();
        assertThat(reasons(result.get("skipped")))
                .containsExactlyInAnyOrder("This task is already done",
                        "Not found, or outside your scope or the current filter");

        assertThat(taskRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        // Untouched, in a branch this caller cannot reach at all.
        assertThat(taskRepository.findById(elsewhere).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void deletingACustomerTakesItsTasksAndTheirAssigneesWithIt() throws Exception {
        actAs(admin);
        Customer doomed = customerService.create(new CustomerDtos.CustomerCreateRequest(
                "Doomed Ltd", null, null, null, null, defaultRegion().getId(),
                "doomed.login", "Password1!"));
        Long going = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                doomed.getId(), "Goes with the account", null, null, List.of(hq.getId()))).id();
        Long staying = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER,
                home.getId(), "Stays", null, null, List.of(hq.getId()))).id();

        // CUSTOMER_DELETE is always checked, so the removal is raised and then approved by
        // somebody else — which is also the path a real deletion takes (B2).
        JsonNode held = read(mockMvc.perform(delete("/api/customers/" + doomed.getId())
                        .with(as(admin)))
                .andExpect(status().isAccepted()));
        mockMvc.perform(post("/api/approvals/" + held.get("pendingChangeId").asLong() + "/approve")
                        .with(as(checker)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(customerRepository.findById(doomed.getId())).isEmpty();
        assertThat(taskRepository.findById(going)).isEmpty();
        // The seats go too: task_assignees.task_id is the one foreign key in the programme, so a
        // seat left behind would point at a task that is gone (A6).
        assertThat(taskAssigneeRepository.findAll())
                .extracting(seat -> seat.getTask().getId())
                .containsExactly(staying);
        assertThat(taskRepository.findById(staying)).isPresent();
    }

    @Test
    void theApplicationStartsWithBothNewTablesClassifiedOnARegionAxis() {
        // RegionCoverageCheck walks the whole metamodel at boot and RegionAxes throws for an
        // entity nobody classified, so the context that is running this assertion is itself the
        // evidence. What is asserted here is WHICH answer each one got: a task is reached through
        // its customer, and a seat is reached only through its task (A6, B1).
        assertThat(RegionAxes.of(Task.class)).isEqualTo(RegionAxis.VIA_CUSTOMER_ID);
        assertThat(RegionAxes.of(TaskAssignee.class)).isEqualTo(RegionAxis.NONE);
        assertThat(RegionAxes.reason(TaskAssignee.class))
                .contains("reached only through the task");
        assertThat(RegionAxes.reason(Task.class)).isEmpty();

        // And the registered list reads the same classification, so the schema and the axis cannot
        // drift apart (B1).
        assertThat(TaskSchemas.TASKS.axis()).isEqualTo(RegionAxis.VIA_CUSTOMER_ID);
        assertThat(TaskSchemas.TASKS.entityType()).isEqualTo(Task.class);
        assertThat(com.geneinvoice.common.query.TableSchemas.entities()).contains("tasks");
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private List<Notification> assigned() {
        return notificationRepository.findAll().stream()
                .filter(n -> TaskService.NOTIF_ASSIGNED.equals(n.getType())).toList();
    }

    private Invoice invoiceFor(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1,
                        new BigDecimal("100.00")))));
    }

    private void seat(Customer c, User u) {
        customerPocRepository.save(CustomerPoc.builder()
                .customer(c).user(u).pocType(PocType.COLLECTION).primary(true).build());
    }

    /**
     * May see and manage tasks, and holds no SCOPE_OVERRIDE so the book bites.
     *
     * <p>POC_ASSIGNABLE_COLLECTION is here for a reason worth naming: creating a task resolves the
     * subject through customerService.get, whose book is ScopeResolver.forCustomers — and that one
     * short-circuits to "nothing" for somebody who is assignable as no POC at all, however many
     * seats they hold. So TASK_MANAGE alone does not let anybody make a task: the record has to be
     * visible on its own screen first, which is exactly the property the 404 above is about (A6).
     */
    private Role taskRole() {
        return roleWith("TASK_WORKER", Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.POC_VIEW, Privileges.POC_ASSIGNABLE_COLLECTION);
    }

    private Role checkerRole() {
        return roleWith("TASK_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by TaskWriteTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private static List<Long> longs(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static List<String> reasons(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.get("reason").asText()).toList();
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
