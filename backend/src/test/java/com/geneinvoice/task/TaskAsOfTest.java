package com.geneinvoice.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tasks as of a date (B3, D8): a task is read from its versions, its assignees from their intervals, and a role
 * assignee means whoever held that seat on that day.
 * <ul>
 *   <li><b>D1</b>: a task on Acme, assigned to Cara and to the customer's Collection POC, whose seat Colin holds.</li>
 *   <li><b>D2</b>: the task is started; Cara makes way for Vic; Colin's seat goes to Cody.</li>
 *   <li><b>D3</b>: the task is done.</li>
 * </ul>
 */
class TaskAsOfTest extends IntegrationTestBase {

    static final LocalDate D0 = LocalDate.of(2026, 3, 1);
    static final LocalDate D1 = D0.plusDays(1);
    static final LocalDate D2 = D0.plusDays(2);
    static final LocalDate D3 = D0.plusDays(3);
    static final LocalDate TODAY = D0.plusDays(4);

    @Autowired TaskService taskService;
    @Autowired PocService pocService;

    User admin;
    User cara;
    User vic;
    User colin;
    User cody;
    Customer acme;
    Long taskId;

    @BeforeEach
    void playTheStory() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        Region north = region("NORTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        cara = staff("cara.collections", DataSeeder.ROLE_COLLECTION_POC, north);
        vic = staff("vic.viewer", "VIEWER", north);
        colin = staff("colin.collections", DataSeeder.ROLE_COLLECTION_POC, north);
        cody = staff("cody.collections", DataSeeder.ROLE_COLLECTION_POC, north);

        on(D1);
        acme = customer("Acme Ltd", north);
        actAs(admin);
        Long colinsSeat = pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), true).getId();
        EmailToken collections = EmailToken.role(RoleRef.customer(EmailRole.COLLECTION_POC));
        taskId = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER, acme.getId(),
                "Call Acme", null, D1.plusDays(1), List.of(EmailToken.user(cara.getId()), collections))).getId();

        on(D2);
        taskService.changeStatus(taskId, TaskStatus.IN_PROGRESS, 0L);
        taskService.patch(taskId, new TaskDtos.PatchTaskRequest(null, null, null, null,
                List.of(EmailToken.user(vic.getId()), collections), 1L));
        pocService.remove(acme.getId(), colinsSeat);
        pocService.add(acme.getId(), PocType.COLLECTION, cody.getId(), true);

        on(D3);
        taskService.changeStatus(taskId, TaskStatus.DONE, 1L);

        SecurityContextHolder.clearContext();
        on(TODAY);
    }

    private User staff(String username, String role, Region region) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        grant(u, region, AccessLevel.READ, RecordScope.ALL);
        return u;
    }

    private static void on(LocalDate day) {
        MovableClock.SHARED.set(day.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
    }

    private ResultActions call(String url, User caller, LocalDate day, String... filters) throws Exception {
        MockHttpServletRequestBuilder request = get(url).with(as(caller));
        if (day != null) request.param("asOf", day.toString());
        for (String f : filters) request.param("filter", f);
        return mockMvc.perform(request);
    }

    private JsonNode read(String url, User caller, LocalDate day, String... filters) throws Exception {
        return objectMapper.readTree(call(url, caller, day, filters).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Long> mine(User caller, LocalDate day) throws Exception {
        List<Long> ids = new ArrayList<>();
        read("/api/tasks/mine", caller, day).get("content").forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    private List<String> assignees(LocalDate day) throws Exception {
        List<String> tokens = new ArrayList<>();
        read("/api/tasks/" + taskId, admin, day).get("assignees").forEach(a -> tokens.add(
                a.at("/token/type").asText().equals("USER") ? "USER " + a.at("/token/userId").asLong()
                        : a.at("/token/role").asText() + "@" + a.at("/token/level").asText()));
        return tokens;
    }

    @Test
    void aTasksStatusIsReadFromItsVersionsOfThatDay() throws Exception {
        long[] reads = new long[1];
        JsonNode[] onD1 = new JsonNode[1];
        reads[0] = CountingStatements.reads("task_versions", () -> onD1[0] = read("/api/tasks/" + taskId, admin, D1));
        assertThat(reads[0]).isPositive();
        assertThat(onD1[0].get("status").asText()).isEqualTo("OPEN");
        assertThat(onD1[0].get("version").isNull()).isTrue();
        assertThat(onD1[0].get("canEdit").asBoolean()).isFalse();
        assertThat(onD1[0].get("canChangeStatus").asBoolean()).isFalse();
        assertThat(onD1[0].get("overdue").asBoolean()).as("due D2, not yet late on D1").isFalse();
        assertThat(read("/api/tasks/" + taskId, admin, D2).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(read("/api/tasks/" + taskId, admin, D2).get("overdue").asBoolean()).isFalse();
        assertThat(read("/api/tasks/" + taskId, admin, D3).get("status").asText()).isEqualTo("DONE");
        assertThat(read("/api/tasks/" + taskId, admin, null).get("status").asText()).isEqualTo("DONE");
        assertThat(read("/api/tasks/" + taskId, admin, null).get("canEdit").asBoolean()).isTrue();
        JsonNode missing = objectMapper.readTree(call("/api/tasks/" + taskId, admin, D0)
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(missing.get("message").asText()).isEqualTo("Task not found");

        assertThat(read("/api/tasks", admin, D1, "status:eq:OPEN").get("totalElements").asLong()).isEqualTo(1);
        assertThat(read("/api/tasks", admin, D2, "status:eq:OPEN").get("totalElements").asLong()).isZero();
        assertThat(read("/api/tasks", admin, D2).get("asOf").asText()).isEqualTo(D2.toString());
        assertThat(read("/api/tasks/summary", admin, D2).get("inProgress").asLong()).isEqualTo(1);
        assertThat(read("/api/tasks/summary", admin, D3).get("overdue").asLong()).isZero();
        assertThat(read("/api/tasks", admin, D0).get("totalElements").asLong()).isZero();
        JsonNode count = read("/api/tasks/count?entityType=CUSTOMER&entityId=" + acme.getId(), admin, D2);
        assertThat(count.get("count").asLong()).isEqualTo(1);
        assertThat(count.get("open").asLong()).isEqualTo(1);
        assertThat(read("/api/tasks/count?entityType=CUSTOMER&entityId=" + acme.getId(), admin, null)
                .get("open").asLong()).isZero();
    }

    @Test
    void theAssigneesOfADayAreThoseWhoseIntervalCoversIt() throws Exception {
        assertThat(assignees(D1)).containsExactlyInAnyOrder("USER " + cara.getId(), "COLLECTION_POC@CUSTOMER");
        assertThat(assignees(D2)).containsExactlyInAnyOrder("USER " + vic.getId(), "COLLECTION_POC@CUSTOMER");
        assertThat(assignees(null)).containsExactlyInAnyOrder("USER " + vic.getId(), "COLLECTION_POC@CUSTOMER");

        assertThat(mine(cara, D1)).containsExactly(taskId);
        assertThat(mine(cara, D2)).isEmpty();
        assertThat(mine(vic, D1)).isEmpty();
        assertThat(mine(vic, D2)).containsExactly(taskId);
        assertThat(read("/api/tasks/" + taskId, cara, D1).get("assignedToMe").asBoolean()).isTrue();
        assertThat(read("/api/tasks/" + taskId, cara, null).get("assignedToMe").asBoolean()).isFalse();
        assertThat(read("/api/tasks", admin, D1, "assigneeUserId:eq:" + cara.getId()).get("totalElements")
                .asLong()).isEqualTo(1);
        assertThat(read("/api/tasks", admin, null, "assigneeUserId:eq:" + cara.getId()).get("totalElements")
                .asLong()).isZero();
    }

    /** "Collection POC (customer)" meant Colin while he held the seat, and Cody once it was his. */
    @Test
    void aRoleAssigneeMeansWhoeverHeldTheSeatThatDay() throws Exception {
        assertThat(mine(colin, D1)).containsExactly(taskId);
        assertThat(mine(colin, D2)).isEmpty();
        assertThat(mine(colin, null)).isEmpty();
        assertThat(mine(cody, D1)).isEmpty();
        assertThat(mine(cody, D2)).containsExactly(taskId);
        assertThat(mine(cody, null)).containsExactly(taskId);
        assertThat(read("/api/tasks/mine/count", colin, D1).get("count").asLong()).isEqualTo(1);

        JsonNode role = read("/api/tasks/" + taskId, admin, D1).get("assignees").get(1);
        assertThat(role.get("label").asText()).isEqualTo("Collection POC (customer)");
        assertThat(role.get("people")).extracting(p -> p.get("userId").asLong()).containsExactly(colin.getId());
        JsonNode later = read("/api/tasks/" + taskId, admin, D2).get("assignees");
        for (JsonNode a : later) {
            if (a.at("/token/type").asText().equals("ROLE")) {
                assertThat(a.get("people")).extracting(p -> p.get("userId").asLong()).containsExactly(cody.getId());
            }
        }
    }
}
