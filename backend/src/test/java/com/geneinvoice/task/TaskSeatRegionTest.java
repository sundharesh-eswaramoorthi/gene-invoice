package com.geneinvoice.task;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionCustodyService;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A SEAT ON A TASK IS A SEAT IN A BRANCH, on both halves of the rule the POC book has had since R8
 * (A6, B1).
 *
 * <p>Task is VIA_CUSTOMER_ID, so TableQueryExecutor ANDs the region axis OVER the assignee arm of
 * ScopeResolver.forTasks: a seat held by somebody with no grant in the account's branch is a seat
 * on a row that answers 404 to its own holder. Before this, both halves were missing — nothing
 * checked the assignee's branch when the seat was written, and nothing revisited the seats when
 * the account moved — so the product could write, NOTIFY and then hide the same piece of work.
 *
 * <p>The two halves are tested apart because they fail apart: the gate is TaskService's and the
 * vacation is TaskRegionSync's, and a build with one and not the other still ends in a stranded
 * seat by the other door.
 */
class TaskSeatRegionTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired TaskService taskService;
    @Autowired RegionCustodyService custodyService;

    User admin;
    /** Works in the default branch only, at MANAGE — the ordinary staffed person. */
    User hank;
    /** Works up north only. */
    User nora;
    Region north;
    Customer home;
    Customer away;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        hank = user("hank.hq", taskRole().getName());
        nora = user("nora.north", taskRole().getName());
        revokeRegionGrants(nora);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(nora.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());
        home = customer("Home Ltd");
        away = customerRepository.save(Customer.builder().name("Away Ltd").region(north).build());
        // IntegrationTestBase.customer writes no placement row and RegionCustodyService closes one
        // to open the next, so a fixture account has to be placed before it can be moved (B1).
        place(home, defaultRegion());
        place(away, north);
        actAs(admin);
    }

    /**
     * THE GATE. An administrator holds the wildcard and may write in NORTH; Hank works in the
     * default branch only. Giving him work on a northern account is refused for what it is — a
     * statement about the PERSON named, so a 400 naming the branch and not a 403 about the
     * caller's own reach — and nothing at all is written or sent (A6, B1, D-46).
     */
    @Test
    void somebodyWhoDoesNotWorkInTheBranchCannotBeGivenWorkThere() {
        Throwable refused = catchThrowable(() -> taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, away.getId(), "Chase the northern PO", null, null,
                List.of(hank.getId()))));

        assertThat(refused).hasMessageContaining(hank.getUsername())
                .hasMessageContaining("does not work in")
                .hasMessageContaining(north.getCode());
        assertThat(taskAssigneeRepository.findAll()).isEmpty();
        // And nobody was told about work that would have answered 404 to them.
        assertThat(notificationRepository.findAll())
                .noneMatch(n -> TaskService.NOTIF_ASSIGNED.equals(n.getType()));

        // The same person on an account in their own branch is exactly as assignable as before.
        TaskDtos.TaskDto fine = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Chase the PO", null, null,
                List.of(hank.getId())));
        assertThat(fine.assignees()).extracting(TaskDtos.AssigneeDto::userId)
                .containsExactly(hank.getId());
    }

    /** Reassigning is the same gate: it delegates to update, which shares the write tail (A6). */
    @Test
    void reassigningToSomebodyOutsideTheBranchIsRefusedToo() {
        TaskDtos.TaskDto theirs = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, away.getId(), "Chase the northern PO", null, null,
                List.of(nora.getId())));

        assertThat(catchThrowable(() -> taskService.reassign(theirs.id(), List.of(hank.getId()))))
                .hasMessageContaining("does not work in");
        assertThat(taskAssigneeRepository.findByTaskIdOrderByIdAsc(theirs.id()))
                .extracting(TaskAssignee::getUserId).containsExactly(nora.getId());
    }

    /**
     * THE MOVE. The seat was valid when it was written and the branch changed underneath it, which
     * is the one way the state the gate refuses can still come about. The account moves north:
     * Hank's seat goes, Nora's — she works there — stays, and the administrator's wildcard keeps
     * his.
     *
     * <p>The read is asserted as well as the row, because the row is not the point: before this,
     * Hank kept a seat on a task his own list no longer showed him and his single-record GET
     * answered 404 for.
     */
    @Test
    void movingAnAccountVacatesTheSeatsOfPeopleWhoDoNotWorkWhereItWent() throws Exception {
        TaskDtos.TaskDto task = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Chase the PO", null, null,
                List.of(hank.getId(), admin.getId())));
        // Hank can see it while the account is in his branch, which is what the move takes away.
        mockMvc.perform(get("/api/tasks/" + task.id()).with(as(hank)))
                .andExpect(status().isOk());

        actAs(admin);
        custodyService.move(home.getId(), north.getId(), null, "reorganised");

        assertThat(taskAssigneeRepository.findByTaskIdOrderByIdAsc(task.id()))
                .extracting(TaskAssignee::getUserId).containsExactly(admin.getId());
        mockMvc.perform(get("/api/tasks/" + task.id()).with(as(hank)))
                .andExpect(status().isNotFound());
    }

    /**
     * A FINISHED TASK RECORDS WHO DID IT. Vacating the seat off a completed task would rewrite
     * that, so only work still owed is revisited (A6).
     */
    @Test
    void aCompletedTaskKeepsTheNameOfWhoeverDidIt() {
        TaskDtos.TaskDto done = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, home.getId(), "Already chased", null, null,
                List.of(hank.getId())));
        taskService.complete(done.id());

        custodyService.move(home.getId(), north.getId(), null, "reorganised");

        assertThat(taskAssigneeRepository.findByTaskIdOrderByIdAsc(done.id()))
                .extracting(TaskAssignee::getUserId).containsExactly(hank.getId());
    }

    private void place(Customer c, Region where) {
        customerRegionHistoryRepository.saveAndFlush(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(where.getId())
                .validFrom(LocalDate.now(ZoneOffset.UTC).minusDays(30)).build());
    }

    private Role taskRole() {
        return roleWith("TASK_SEAT_REGION", Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                // SCOPE_OVERRIDE, so the POC book contributes nothing and what a caller can see
                // here is the region axis alone (B1).
                Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by TaskSeatRegionTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
