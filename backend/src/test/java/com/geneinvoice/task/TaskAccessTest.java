package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who can see which tasks. Two independent narrowings are at work and the file is written to keep
 * them apart: the POC BOOK, contributed by ScopeResolver.forTasks, and the REGION axis, ANDed in
 * by TableQueryExecutor because Task is classified VIA_CUSTOMER_ID and not because anybody here
 * remembered to pass it (A6, B1).
 */
class TaskAccessTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired CustomerRepository customers;
    @Autowired TaskService taskService;

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    User admin;
    User hq;
    User loner;
    Region north;
    Customer home;
    Customer away;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        // A person who may see and manage tasks and has no SCOPE_OVERRIDE, so the book bites.
        // user(...) staffs them in the default region at the levels their role implies (B1).
        hq = user("hank.hq", taskRole().getName());
        loner = user("lara.loner", taskRole().getName());
        home = customer("Home Ltd");
        away = customers.save(Customer.builder().name("Away Ltd").region(north).build());
        // Hank keeps a seat on BOTH accounts, so anything he cannot see about the NORTH one is
        // the region talking and not the book (B1).
        seat(home, hq);
        seat(away, hq);
    }

    @Test
    void aTaskOnACustomerInAnotherBranchIsNotInMyListAndAnswers404ById() throws Exception {
        Task mine = taskOn(home, "Chase the PO");
        Task theirs = taskOn(away, "Chase the northern PO");

        JsonNode listed = read(mockMvc.perform(get("/api/tasks").with(as(hq)))
                .andExpect(status().isOk()));
        assertThat(ids(listed)).containsExactly(mine.getId());

        mockMvc.perform(get("/api/tasks/" + mine.getId()).with(as(hq)))
                .andExpect(status().isOk());
        // 404 and never 403: a record the caller's regions exclude reads as one that does not
        // exist, or the refusal becomes an oracle for which ids are real (AUTH-08).
        mockMvc.perform(get("/api/tasks/" + theirs.getId()).with(as(hq)))
                .andExpect(status().isNotFound());

        // And the branch is the only thing keeping it out: somebody who works there sees it.
        User nora = user("nora.north", taskRole().getName());
        grant(nora, north);
        seat(away, nora);
        assertThat(ids(read(mockMvc.perform(get("/api/tasks").with(as(nora)))
                .andExpect(status().isOk())))).contains(theirs.getId());
    }

    @Test
    void aTaskIsMineWhenIAmOnItEvenThoughItsCustomerIsNotInMyBook() throws Exception {
        Task handed = taskOn(home, "Call them back", loner);
        Task somebodyElses = taskOn(home, "Not yours");

        // Lara holds no POC seat and owns no invoice, so the book half of forTasks is false for
        // both rows. The seat on the task is the only thing that puts one of them in her list —
        // which is what makes "my work" mean something to somebody who is nobody's POC (A6).
        assertThat(customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(home.getId()))
                .noneMatch(seat -> seat.getUser().getId().equals(loner.getId()));

        JsonNode listed = read(mockMvc.perform(get("/api/tasks").with(as(loner)))
                .andExpect(status().isOk()));
        assertThat(ids(listed)).containsExactly(handed.getId());
        mockMvc.perform(get("/api/tasks/" + somebodyElses.getId()).with(as(loner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListSaysWhichBookAndWhichBranchesItCovers() throws Exception {
        taskOn(home, "Chase the PO");

        JsonNode narrowed = read(mockMvc.perform(get("/api/tasks").with(as(hq)))
                .andExpect(status().isOk()));
        // Book chips first, then region chips: the order PageResponse.of fixes, and the region one
        // comes from regionScope.lockedFilters(Task.class) rather than from the Scope (B1).
        assertThat(chips(narrowed)).containsExactly(
                "myTasks:eq:" + hq.getId(), "regionId:in:" + defaultRegion().getId());

        // A wildcard holder is narrowed by neither, and says so by saying nothing.
        JsonNode everything = read(mockMvc.perform(get("/api/tasks").with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(chips(everything)).isEmpty();
    }

    @Test
    void aCustomerLoginSeesOnlyTheTasksAboutItsOwnRecordsAndCanOwnNone() throws Exception {
        Customer other = customer("Other Ltd");
        Task ours = taskOn(home, "About the account that logged in", hq);
        Task theirs = taskOn(other, "About somebody else");

        // What a member of staff sees on the same row, for the contrast the last assertion needs.
        assertThat(read(mockMvc.perform(get("/api/tasks/" + ours.getId()).with(as(admin)))
                .andExpect(status().isOk())).get("assignees")).hasSize(1);

        User login = customerUser("home.login", home.getId());
        // The CUSTOMER role holds no TASK_VIEW, so this is a posture no installation ships with;
        // the clause in forTasks is here so that it would still hold if somebody granted it (A6).
        login.setRole(taskRole());
        login = userRepository.save(login);

        JsonNode listed = read(mockMvc.perform(get("/api/tasks").with(as(login)))
                .andExpect(status().isOk()));
        assertThat(ids(listed)).containsExactly(ours.getId());
        mockMvc.perform(get("/api/tasks/" + theirs.getId()).with(as(login)))
                .andExpect(status().isNotFound());

        // A customer login is not an internal person, so it can never be given work in the
        // supplier's own book — the EmailAddressing.activeInternalUser rule (A6).
        Long loginId = login.getId();
        actAs(admin);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                taskService.update(ours.getId(), new TaskDtos.UpdateTaskRequest(
                        null, null, null, null, List.of(loginId)))))
                .hasMessageContaining("is not an active internal user");

        // And what it does see carries no internal names: who in the supplier's staff is doing
        // the work is the same fact the POC columns are pocRestricted for (A6).
        JsonNode row = listed.get("content").get(0);
        assertThat(row.get("assignees")).isEmpty();
    }

    @Test
    void theTilesAndTheListAgreeBecauseTheyRunTheSameFilters() throws Exception {
        taskDue(home, "Late one", TODAY.minusDays(3));
        taskDue(home, "Also late", TODAY.minusDays(1));
        taskDue(home, "Due soon", TODAY.plusDays(2));
        taskDue(home, "Due later", TODAY.plusDays(40));
        // Out of the caller's branch entirely: it must be absent from BOTH numbers, which is the
        // half of this that a tile computed off a repository count would get wrong (A6, B1).
        taskDue(away, "Late up north", TODAY.minusDays(9));

        // Unfiltered: four rows in Hank's branch, two of them late and one due inside the week.
        // The northern row is in neither number, which is the half a tile computed off a
        // repository count would get wrong (A6, B1).
        JsonNode tiles = read(mockMvc.perform(get("/api/tasks/summary").with(as(hq)))
                .andExpect(status().isOk()));
        assertThat(tiles.get("open").asLong()).isEqualTo(4);
        assertThat(tiles.get("overdue").asLong()).isEqualTo(2);
        assertThat(tiles.get("dueThisWeek").asLong()).isEqualTo(1);
        assertThat(read(mockMvc.perform(get("/api/tasks").with(as(hq))).andExpect(status().isOk()))
                .get("totalElements").asLong()).isEqualTo(4);

        // And under a chip, the tile and the rows behind it are one number. Each chip below
        // deliberately narrows PAST the tile's own predicate — one of the two late rows, three of
        // the four open ones — so a summary that ignored the list's filters would answer 2 and 4
        // here and the agreement would break (A6).
        assertThat(agree(hq, "overdue", "overdue:eq:true", "dueDate:gte:" + TODAY.minusDays(2)))
                .isEqualTo(1);
        assertThat(agree(hq, "open", "status:eq:OPEN", "dueDate:lte:" + TODAY.plusDays(3)))
                .isEqualTo(3);

        // The same run for somebody who can see the northern row proves the numbers move with the
        // caller rather than being constants this test happens to agree with.
        User nora = user("nora.north", taskRole().getName());
        grant(nora, north);
        seat(away, nora);
        assertThat(agree(nora, "overdue", "overdue:eq:true")).isEqualTo(1);
    }

    @Test
    void theMineCountGoesThroughTheExecutorSoItInheritsBookAndBranch() throws Exception {
        taskOn(home, "Mine and here", hq);
        // A seat Hank holds on an account in a branch he does not work in, written straight to the
        // table because the product no longer writes one: TaskService.requireAssignable refuses
        // the assignment and TaskRegionSync vacates the seat when an account moves. It is still a
        // reachable row — revoking somebody's grant after they were assigned leaves exactly this —
        // and the READ side is what this test is about: the badge is the caller's book AND their
        // branches, never a count over task_assignees (A6, B1).
        strandedSeat(taskOn(away, "Mine but up north"), hq);
        taskOn(home, "Here but not mine");
        Task done = taskOn(home, "Mine and finished", hq);
        actAs(admin);
        taskService.complete(done.getId());

        // Four rows carry Hank's name or his book; exactly one is open, mine and in a branch he
        // works in. A repository countBy… would answer two, which is the whole reason the badge
        // goes through queryExecutor.count (A6, B1).
        assertThat(taskAssigneeRepository.findAll())
                .filteredOn(seat -> seat.getUserId().equals(hq.getId()))
                .hasSize(3);
        assertThat(count(hq, true)).isEqualTo(1);

        // mine=false is the same funnel with one chip fewer: everything open he can reach.
        assertThat(count(hq, false)).isEqualTo(2);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private long count(User who, boolean mine) throws Exception {
        return read(mockMvc.perform(get("/api/tasks/count").param("mine", String.valueOf(mine))
                .with(as(who))).andExpect(status().isOk())).get("count").asLong();
    }

    private long agree(User who, String tile, String... chips) throws Exception {
        JsonNode tiles = read(mockMvc.perform(get("/api/tasks/summary").param("filter", chips)
                .with(as(who))).andExpect(status().isOk()));
        JsonNode rows = read(mockMvc.perform(get("/api/tasks").param("filter", chips)
                .with(as(who))).andExpect(status().isOk()));
        // The claim is not "the tile is 2"; it is "the tile and the rows behind the chips are the
        // same number", which is what running them through one schema and one scope buys (A6).
        assertThat(tiles.get(tile).asLong())
                .describedAs("tile %s and the list under %s must agree", tile, List.of(chips))
                .isEqualTo(rows.get("totalElements").asLong());
        return tiles.get(tile).asLong();
    }

    /** The one seat the service will not write, for the read side that still has to survive it. */
    private void strandedSeat(Task task, User who) {
        taskAssigneeRepository.saveAndFlush(TaskAssignee.builder()
                .task(task).userId(who.getId()).source(TaskAssignee.SOURCE_USER).build());
    }

    private Task taskOn(Customer c, String title, User... assignees) {
        return taskDue(c, title, null, assignees);
    }

    private Task taskDue(Customer c, String title, LocalDate due, User... assignees) {
        actAs(admin);
        TaskDtos.TaskDto dto = taskService.create(new TaskDtos.CreateTaskRequest(
                TaskEntityType.CUSTOMER, c.getId(), title, null, due,
                Arrays.stream(assignees).map(User::getId).toList()));
        return taskRepository.findById(dto.id()).orElseThrow();
    }

    /**
     * A seat in the book, written the way the product writes one: the FIRST seat of a type on an
     * account is its primary and every later one is not, which is PocService.add's own rule.
     *
     * <p>It used to mark EVERY seat primary, which is a row the product can never write and the
     * database refuses: uk_customer_poc_primary is a partial unique index on
     * {@code (customer_id, poc_type) where is_primary}, so the second seat on one account died
     * with a duplicate key on Postgres while H2, which has no partial index, took it. Nothing in
     * this file turns on WHICH seat is primary — ScopeResolver.forTasks matches a seat by
     * customer and holder and never reads is_primary — so this narrows nothing (A6, B1, CP-02).
     */
    private void seat(Customer c, User u) {
        boolean firstOfItsType = customerPocRepository
                .findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc(c.getId(), PocType.COLLECTION)
                .isEmpty();
        customerPocRepository.save(CustomerPoc.builder()
                .customer(c).user(u).pocType(PocType.COLLECTION).primary(firstOfItsType).build());
    }

    private void grant(User u, Region where) {
        userRegionGrantRepository.save(com.geneinvoice.region.UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId())
                .right(com.geneinvoice.region.RegionRight.MANAGE).build());
    }

    /** May see and manage tasks, and holds no SCOPE_OVERRIDE, so the book actually bites. */
    private Role taskRole() {
        return roleWith("TASK_WORKER", Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.POC_VIEW);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by TaskAccessTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private static List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(n -> n.get("id").asLong()).toList();
    }

    private static List<String> chips(JsonNode page) {
        return StreamSupport.stream(page.get("lockedFilters").spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
