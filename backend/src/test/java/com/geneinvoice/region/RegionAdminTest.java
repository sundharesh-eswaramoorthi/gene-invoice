package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regions are created, changed, made the default and deleted by those who manage regions; region access is
 * given by those who manage users, and never beyond what they hold themselves (SC-5).
 */
class RegionAdminTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired Environment environment;

    User admin;
    Region north;
    Region south;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private ResultActions send(MockHttpServletRequestBuilder request, User as, Object body) throws Exception {
        request.with(as(as));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        return mockMvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> row(Region r, String level, String scope) {
        return Map.of("regionId", r.getId(), "level", level, "scope", scope);
    }

    private static Map<String, Object> grants(Object... rows) {
        return Map.of("grants", List.of(rows));
    }

    /** A staff user holding exactly these grants, and nothing in the default region. */
    private User staff(String username, String role, Object... regionLevelScope) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        for (int i = 0; i < regionLevelScope.length; i += 3) {
            grant(u, (Region) regionLevelScope[i], (AccessLevel) regionLevelScope[i + 1],
                    (RecordScope) regionLevelScope[i + 2]);
        }
        return u;
    }

    private Role roleWith(String name, String... privileges) {
        Role role = roleRepository.findByName(name)
                .orElseGet(() -> Role.builder().name(name).description(name).build());
        role.setPrivileges(Stream.of(privileges).map(p -> privilegeRepository.findByName(p).orElseThrow())
                .collect(Collectors.toCollection(HashSet::new)));
        return roleRepository.save(role);
    }

    /** Every grant in the database, as "user:REGION:LEVEL/SCOPE", so a refusal can be shown to change nothing. */
    private List<String> allGrants() {
        return jdbc.queryForList("""
                select u.username || ':' || r.code || ':' || g.access_level || '/' || g.record_scope
                  from user_region_grants g join users u on u.id = g.user_id join regions r on r.id = g.region_id
                 order by 1""", String.class);
    }

    private List<String> allRegions() {
        return jdbc.queryForList("select code || ':' || name || ':' || active || ':' || is_default || ':' || version"
                + " from regions order by code", String.class);
    }

    /** Audit rows outlive a test, so counts are taken per record or compared with a count taken first. */
    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    private long auditCount(String action, Long entityId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ? and entity_id = ?", Long.class,
                action, entityId);
    }

    private List<String> actions(JsonNode history) {
        return StreamSupport.stream(history.spliterator(), false).map(n -> n.get("action").asText()).toList();
    }

    private JsonNode createRegion(String code, String name) throws Exception {
        return read(send(post("/api/regions"), admin, Map.of("code", code, "name", name)).andExpect(status().isOk()));
    }

    // ---- regions ------------------------------------------------------------------------------------------

    @Test
    void aRegionIsCreatedReadListedAndChangedAndEveryChangeIsInItsHistory() throws Exception {
        JsonNode east = read(send(post("/api/regions"), admin, Map.of("code", " east ", "name", " East ", "active", true))
                .andExpect(status().isOk()));
        long id = east.get("id").asLong();
        assertThat(east.get("code").asText()).isEqualTo("EAST");
        assertThat(east.get("name").asText()).isEqualTo("East");
        assertThat(east.get("active").asBoolean()).isTrue();
        assertThat(east.get("defaultRegion").asBoolean()).isFalse();
        assertThat(east.get("version").asLong()).isZero();
        assertThat(east.hasNonNull("createdAt")).isTrue();

        assertThat(grantRepository.findByUserIdAndRegionId(admin.getId(), id)).get()
                .as("whoever creates a region can work in it straight away")
                .satisfies(g -> {
                    assertThat(g.getAccessLevel()).isEqualTo(AccessLevel.APPROVE);
                    assertThat(g.getRecordScope()).isEqualTo(RecordScope.ALL);
                });
        assertThat(grantRepository.countByRegionId(id)).as("and nobody else").isEqualTo(1);

        send(get("/api/regions/" + id), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("EAST"));
        JsonNode page = read(send(get("/api/regions").param("sort", "code,asc"), admin, null)
                .andExpect(status().isOk()));
        assertThat(page.get("content")).extracting(n -> n.get("code").asText())
                .containsExactly("DEFAULT", "EAST", "NORTH", "SOUTH");
        assertThat(page.get("regionIds")).as("regions are configuration, not a set of one region").isEmpty();
        assertThat(read(send(get("/api/regions").param("filter", "code:eq:EAST"), admin, null)
                .andExpect(status().isOk())).get("content")).extracting(n -> n.get("id").asLong())
                .containsExactly(id);
        assertThat(read(send(get("/api/regions").param("filter", "defaultRegion:eq:true"), admin, null)
                .andExpect(status().isOk())).get("content")).extracting(n -> n.get("code").asText())
                .containsExactly(Region.DEFAULT_CODE);

        JsonNode changed = read(send(put("/api/regions/" + id), admin,
                Map.of("name", "East & Islands", "active", false, "version", 0)).andExpect(status().isOk()));
        assertThat(changed.get("name").asText()).isEqualTo("East & Islands");
        assertThat(changed.get("active").asBoolean()).isFalse();
        assertThat(changed.get("code").asText()).as("the code never changes").isEqualTo("EAST");
        assertThat(changed.get("version").asLong()).isEqualTo(1);

        JsonNode history = read(send(get("/api/audit").param("entityType", "REGION").param("entityId", "" + id),
                admin, null).andExpect(status().isOk()));
        assertThat(actions(history)).containsExactly("REGION_UPDATED", "REGION_CREATED");
        assertThat(history.get(0).get("changedByUsername").asText()).isEqualTo("admin");
    }

    @Test
    void aSaveComposedAgainstAnOlderVersionIsRefused() throws Exception {
        send(put("/api/regions/" + north.getId()), admin, Map.of("name", "North One", "version", 0))
                .andExpect(status().isOk());
        List<String> before = allRegions();

        send(put("/api/regions/" + north.getId()), admin, Map.of("name", "North Two", "version", 0))
                .andExpect(status().isConflict());

        assertThat(allRegions()).isEqualTo(before);
        assertThat(regionRepository.findById(north.getId()).orElseThrow().getName()).isEqualTo("North One");
    }

    @Test
    void aBadOrTakenCodeOrNameIsReportedAgainstItsField() throws Exception {
        List<String> before = allRegions();
        long created = auditCount("REGION_CREATED");
        for (String code : List.of("X", "NO RTH", "WAY_TOO_LONG_A_CODE_HERE", "é1")) {
            send(post("/api/regions"), admin, Map.of("code", code, "name", "Somewhere " + code))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.code").exists());
        }
        send(post("/api/regions"), admin, Map.of("code", "north", "name", "Another north"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").value("A region with this code already exists"));
        send(post("/api/regions"), admin, Map.of("code", "SOUTH2", "name", " south "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("A region with this name already exists"));
        send(post("/api/regions"), admin, Map.of("code", "WEST", "name", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        send(put("/api/regions/" + north.getId()), admin, Map.of("name", "SOUTH", "version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("A region with this name already exists"));
        // A save that says nothing of the version it was composed against could overwrite anything.
        send(put("/api/regions/" + north.getId()), admin, Map.of("name", "Up North"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.version").exists());
        // A region may change the case of its own name.
        send(put("/api/regions/" + north.getId()), admin, Map.of("name", "NORTH", "version", 0))
                .andExpect(status().isOk());

        assertThat(allRegions()).hasSize(before.size());
        assertThat(auditCount("REGION_CREATED")).isEqualTo(created);
    }

    @Test
    void makingARegionTheDefaultLeavesExactlyOneDefault() throws Exception {
        Region old = defaultRegion();
        JsonNode made = read(send(post("/api/regions/" + north.getId() + "/make-default"), admin, null)
                .andExpect(status().isOk()));
        assertThat(made.get("defaultRegion").asBoolean()).isTrue();

        assertThat(regionRepository.findAll()).filteredOn(Region::isDefaultRegion)
                .extracting(Region::getCode).containsExactly("NORTH");
        assertThat(regionRepository.findById(old.getId()).orElseThrow().isDefaultRegion()).isFalse();
        JsonNode history = read(send(get("/api/audit").param("entityType", "REGION")
                .param("entityId", "" + north.getId()), admin, null).andExpect(status().isOk()));
        assertThat(actions(history)).containsExactly("REGION_DEFAULT_CHANGED");
        assertThat(history.get(0).get("reason").asText()).contains(Region.DEFAULT_CODE);

        // Making the default the default again changes nothing.
        send(post("/api/regions/" + north.getId() + "/make-default"), admin, null)
                .andExpect(status().isOk());
        assertThat(auditCount("REGION_DEFAULT_CHANGED", north.getId())).isEqualTo(1);

        south.setActive(false);
        regionRepository.save(south);
        send(post("/api/regions/" + south.getId() + "/make-default"), admin, null)
                .andExpect(status().isBadRequest());
        assertThat(regionRepository.findAll()).filteredOn(Region::isDefaultRegion)
                .extracting(Region::getCode).containsExactly("NORTH");
    }

    @Test
    void theDefaultRegionCanBeNeitherDeactivatedNorDeleted() throws Exception {
        Region dflt = defaultRegion();
        List<String> before = allRegions();

        send(put("/api/regions/" + dflt.getId()), admin,
                Map.of("name", dflt.getName(), "active", false, "version", dflt.getVersion()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The default region cannot be deactivated"));
        send(delete("/api/regions/" + dflt.getId()), admin, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Region is the default"));

        assertThat(allRegions()).isEqualTo(before);
    }

    @Test
    void onlyAnEmptyRegionIsDeletedAndItsHistoryOutlivesIt() throws Exception {
        long east = createRegion("EAST", "East").get("id").asLong();
        send(delete("/api/regions/" + east), admin, null).andExpect(status().isNoContent());
        assertThat(regionRepository.findById(east)).isEmpty();
        assertThat(grantRepository.countByRegionId(east)).as("the creator's own grant went with it").isZero();
        JsonNode history = read(send(get("/api/audit").param("entityType", "REGION").param("entityId", "" + east),
                admin, null).andExpect(status().isOk()));
        assertThat(actions(history)).containsExactly("REGION_DELETED", "REGION_CREATED");
        send(get("/api/regions/" + east), admin, null).andExpect(status().isNotFound());

        customer("Northern Ltd", north);
        User sam = staff("sam.south", DataSeeder.ROLE_SALES_POC, south, AccessLevel.READ, RecordScope.BOOK);
        List<String> regions = allRegions();
        List<String> grants = allGrants();
        long deleted = auditCount("REGION_DELETED");
        for (Region inUse : List.of(north, south)) {
            send(delete("/api/regions/" + inUse.getId()), admin, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("Region still has customers, disputes or grants; deactivate it instead"));
        }
        assertThat(allRegions()).isEqualTo(regions);
        assertThat(allGrants()).isEqualTo(grants).contains("sam.south:SOUTH:READ/BOOK");
        assertThat(auditCount("REGION_DELETED")).isEqualTo(deleted);
    }

    /**
     * A region edit, delete or make-default reads the region under the row lock a make-default takes, so what it
     * checked still holds when it writes: a save composed before a make-default never writes the old flag back,
     * and the region just made the default is never deleted. Each case holds the lock in another session and
     * changes the default there, as a make-default committing mid-request would.
     */
    @Test
    void aRegionChangeWaitsForAMakeDefaultAndNeverUndoesIt() throws Exception {
        Region dflt = defaultRegion();

        Future<?> edit = whileLocked(List.of(dflt, north), () -> send(put("/api/regions/" + north.getId()), admin,
                Map.of("name", "North Edited", "version", 0)), held -> switchDefault(held, dflt, north));
        assertThat(((ResultActions) edit.get(10, TimeUnit.SECONDS)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(defaults()).as("the edit left the new default as it was").containsExactly("NORTH");
        assertThat(regionRepository.findById(north.getId()).orElseThrow().getName()).isEqualTo("North Edited");

        Future<?> editOld = whileLocked(List.of(north, south), () -> send(put("/api/regions/" + north.getId()),
                admin, Map.of("name", "North Again", "version", 1)), held -> switchDefault(held, north, south));
        assertThat(((ResultActions) editOld.get(10, TimeUnit.SECONDS)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(defaults()).as("an edit of the old default does not make it the default again")
                .containsExactly("SOUTH");

        long east = createRegion("EAST", "East").get("id").asLong();
        Region eastRegion = regionRepository.findById(east).orElseThrow();
        Future<?> delete = whileLocked(List.of(south, eastRegion), () -> send(delete("/api/regions/" + east), admin,
                null), held -> switchDefault(held, south, eastRegion));
        ResultActions refused = (ResultActions) delete.get(10, TimeUnit.SECONDS);
        refused.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Region is the default"));
        assertThat(defaults()).containsExactly("EAST");

        // A make-default waits for the old default too, and whichever commits last is the one default.
        Future<?> make = whileLocked(List.of(eastRegion), () -> send(post("/api/regions/" + north.getId()
                + "/make-default"), admin, null), held -> switchDefault(held, eastRegion, dflt));
        ((ResultActions) make.get(10, TimeUnit.SECONDS)).andExpect(status().isOk());
        assertThat(defaults()).containsExactly("NORTH");
    }

    private List<String> defaults() {
        return jdbc.queryForList("select code from regions where is_default = true order by code", String.class);
    }

    /** In the lock holder's session: what a make-default of {@code to} would commit. */
    private static void switchDefault(Connection held, Region from, Region to) throws SQLException {
        try (Statement st = held.createStatement()) {
            st.executeUpdate("update regions set is_default = false where id = " + from.getId());
            st.executeUpdate("update regions set is_default = true where id = " + to.getId());
        }
    }

    /**
     * Runs {@code request} while another session holds these regions' rows, checks it waits for them, lets that
     * session make its change and commit, and hands back the request to be awaited.
     */
    private Future<?> whileLocked(List<Region> rows, Callable<?> request, SqlChange change) throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"), "")) {
            holder.setAutoCommit(false);
            for (Region r : rows) {
                try (PreparedStatement st = holder.prepareStatement("select id from regions where id = ? for update")) {
                    st.setLong(1, r.getId());
                    try (ResultSet rs = st.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
            }
            Future<?> running = writer.submit(request);
            awaitWaitingOnALock(running);
            change.apply(holder);
            holder.commit();
            running.get(10, TimeUnit.SECONDS);
            return running;
        } finally {
            writer.shutdown();
        }
    }

    @FunctionalInterface
    private interface SqlChange {
        void apply(Connection c) throws SQLException;
    }

    private void awaitWaitingOnALock(Future<?> running) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) {
                running.get();
                fail("the region write finished without waiting for the region's lock");
            }
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail("the region write was never seen waiting for the region's lock");
    }

    @Test
    void regionsAreReadByThoseWhoManageRegionsOrUsersAndChangedOnlyByRegionManagers() throws Exception {
        roleWith("USER_ADMIN", Privileges.USER_VIEW, Privileges.USER_MANAGE);
        User userAdmin = user("uma.users", "USER_ADMIN");
        User cashier = userRepository.findByUsername("cashier").orElseThrow();

        send(get("/api/regions"), userAdmin, null).andExpect(status().isOk());
        send(get("/api/regions/" + north.getId()), userAdmin, null).andExpect(status().isOk());
        send(get("/api/table-schemas/regions"), userAdmin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSort").value("code,asc"));
        send(get("/api/table-schemas/regions"), admin, null).andExpect(status().isOk());
        send(get("/api/table-schemas/regions"), cashier, null).andExpect(status().isForbidden());
        assertThat(read(send(get("/api/table-schemas"), cashier, null).andExpect(status().isOk())))
                .extracting(JsonNode::asText).doesNotContain("regions");
        send(get("/api/regions"), cashier, null).andExpect(status().isForbidden());
        // Who holds a region needs REGION_VIEW as well as USER_VIEW.
        send(get("/api/regions/" + north.getId() + "/grants"), userAdmin, null)
                .andExpect(status().isForbidden());

        // Either privilege is enough to read regions; REGION_VIEW alone still shows nobody's access.
        roleWith("REGION_READER", Privileges.REGION_VIEW);
        User regionReader = user("rory.regions", "REGION_READER");
        send(get("/api/regions"), regionReader, null).andExpect(status().isOk());
        send(get("/api/regions/" + north.getId()), regionReader, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("NORTH"));
        send(get("/api/table-schemas/regions"), regionReader, null).andExpect(status().isOk());
        assertThat(read(send(get("/api/table-schemas"), regionReader, null).andExpect(status().isOk())))
                .extracting(JsonNode::asText).contains("regions");
        send(get("/api/regions/" + north.getId() + "/grants"), regionReader, null)
                .andExpect(status().isForbidden());
        send(put("/api/regions/" + north.getId()), regionReader, Map.of("name", "Up North", "version", 0))
                .andExpect(status().isForbidden());

        List<String> before = allRegions();
        send(post("/api/regions"), userAdmin, Map.of("code", "EAST", "name", "East")).andExpect(status().isForbidden());
        send(put("/api/regions/" + north.getId()), userAdmin, Map.of("name", "Up North", "version", 0))
                .andExpect(status().isForbidden());
        send(post("/api/regions/" + north.getId() + "/make-default"), userAdmin, null)
                .andExpect(status().isForbidden());
        send(delete("/api/regions/" + south.getId()), userAdmin, null).andExpect(status().isForbidden());
        send(get("/api/audit").param("entityType", "REGION").param("entityId", "" + north.getId()), cashier, null)
                .andExpect(status().isForbidden());
        assertThat(allRegions()).isEqualTo(before);
    }

    @Test
    void aRegionWriteAskedAsOfAPastDateIsRefused() throws Exception {
        String yesterday = com.geneinvoice.common.time.Today.date().minusDays(1).toString();
        send(put("/api/regions/" + north.getId()).param("asOf", yesterday), admin,
                Map.of("name", "Back then", "version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_READ_ONLY"));
        send(get("/api/regions").param("asOf", yesterday), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").doesNotExist());
        assertThat(regionRepository.findById(north.getId()).orElseThrow().getName()).isEqualTo("North");
    }

    // ---- grants -------------------------------------------------------------------------------------------

    @Test
    void aUsersRegionAccessIsReplacedWholeAndTheChangeNamesWhoMadeIt() throws Exception {
        User rita = user("rita", "CASHIER");

        JsonNode saved = read(send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(row(south, "MANAGE", "BOOK"), row(north, "READ", "ALL"))).andExpect(status().isOk()));
        assertThat(saved).extracting(n -> n.get("regionCode").asText() + ":" + n.get("level").asText()
                        + "/" + n.get("scope").asText())
                .containsExactly("NORTH:READ/ALL", "SOUTH:MANAGE/BOOK");
        assertThat(saved.get(0).get("regionName").asText()).isEqualTo("North");
        assertThat(saved.get(0).get("grantedByUserId").asLong()).isEqualTo(admin.getId());
        assertThat(allGrants()).filteredOn(g -> g.startsWith("rita:"))
                .containsExactly("rita:NORTH:READ/ALL", "rita:SOUTH:MANAGE/BOOK");

        send(get("/api/users/" + rita.getId() + "/region-grants"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionId").value(north.getId()))
                .andExpect(jsonPath("$[1].level").value("MANAGE"));
        send(get("/api/users/" + rita.getId()), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.regionGrants.length()").value(2));
        assertThat(read(send(get("/api/users"), admin, null).andExpect(status().isOk())).get("content"))
                .as("lists carry no grants").allSatisfy(u -> assertThat(u.has("regionGrants")).isFalse());
        JsonNode holders = read(send(get("/api/regions/" + north.getId() + "/grants"), admin, null)
                .andExpect(status().isOk()));
        assertThat(holders).filteredOn(h -> h.get("username").asText().equals("rita")).singleElement()
                .satisfies(h -> {
                    assertThat(h.get("roleName").asText()).isEqualTo("CASHIER");
                    assertThat(h.get("level").asText()).isEqualTo("READ");
                    assertThat(h.get("scope").asText()).isEqualTo("ALL");
                    assertThat(h.get("fullName").asText()).isEqualTo("RITA");
                });
        send(get("/api/auth/me"), rita, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].code").value("NORTH"))
                .andExpect(jsonPath("$.regions[1].code").value("SOUTH"))
                .andExpect(jsonPath("$.regions.length()").value(2));

        JsonNode history = read(send(get("/api/audit").param("entityType", "USER")
                .param("entityId", "" + rita.getId()), admin, null).andExpect(status().isOk()));
        assertThat(history).filteredOn(n -> n.get("action").asText().equals("USER_REGION_GRANTS_CHANGED"))
                .singleElement().satisfies(n -> {
                    assertThat(n.get("changedByUsername").asText()).isEqualTo("admin");
                    assertThat(n.get("beforeJson").asText()).contains(Region.DEFAULT_CODE);
                    assertThat(n.get("afterJson").asText()).contains("NORTH").contains("SOUTH")
                            .doesNotContain(Region.DEFAULT_CODE);
                });

        Instant grantedAt = grantRepository.findByUserIdAndRegionId(rita.getId(), north.getId())
                .orElseThrow().getGrantedAt();
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(row(north, "READ", "ALL"), row(south, "MANAGE", "BOOK"))).andExpect(status().isOk());
        assertThat(auditCount("USER_REGION_GRANTS_CHANGED", rita.getId()))
                .as("sending the same access changes nothing").isEqualTo(1);
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(row(north, "READ", "ALL"))).andExpect(status().isOk());
        assertThat(grantRepository.findByUserIdAndRegionId(rita.getId(), north.getId()).orElseThrow().getGrantedAt())
                .as("a row that stays as it was keeps when it was given").isEqualTo(grantedAt);
        assertThat(allGrants()).filteredOn(g -> g.startsWith("rita:")).containsExactly("rita:NORTH:READ/ALL");

        send(put("/api/users/" + rita.getId() + "/region-grants"), admin, grants()).andExpect(status().isOk());
        assertThat(allGrants()).filteredOn(g -> g.startsWith("rita:")).isEmpty();
        send(get("/api/auth/me"), rita, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(0));
    }

    @Test
    void nobodyChangesTheirOwnAccessNorACustomerLoginsAndEachRegionIsNamedOnce() throws Exception {
        User rita = user("rita", "CASHIER");
        Customer acme = customer("Acme", north);
        User acmeLogin = customerUser("acme.login", acme.getId());
        long missing = regionRepository.findAll().stream().mapToLong(Region::getId).max().orElseThrow() + 1000;
        List<String> before = allGrants();
        long audits = auditCount("USER_REGION_GRANTS_CHANGED");

        send(put("/api/users/" + admin.getId() + "/region-grants"), admin, grants(row(north, "READ", "ALL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot change your own region access"));
        send(put("/api/users/" + acmeLogin.getId() + "/region-grants"), admin, grants(row(north, "READ", "ALL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Customer logins take their region from their customer"));
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(row(north, "READ", "ALL"), row(north, "MANAGE", "ALL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.grants").value("Region NORTH is listed more than once"));
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(Map.of("regionId", missing, "level", "READ", "scope", "ALL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.grants").value("Region " + missing + " does not exist"));
        Map<String, Object> noLevel = new HashMap<>();
        noLevel.put("regionId", north.getId());
        noLevel.put("scope", "ALL");
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin, grants(noLevel))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['fieldErrors']['grants[0].level']").exists());
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin,
                grants(Map.of("regionId", north.getId(), "level", "OWNER", "scope", "ALL")))
                .andExpect(status().isBadRequest());
        send(put("/api/users/" + rita.getId() + "/region-grants"), admin, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.grants").exists());
        send(put("/api/users/999999/region-grants"), admin, grants()).andExpect(status().isNotFound());
        send(get("/api/users/999999/region-grants"), admin, null).andExpect(status().isNotFound());
        send(get("/api/regions/999999/grants"), admin, null).andExpect(status().isNotFound());

        assertThat(allGrants()).isEqualTo(before);
        assertThat(auditCount("USER_REGION_GRANTS_CHANGED")).isEqualTo(audits);
    }

    /**
     * Nadia administers users but works only in NORTH, at MANAGE. She may shape anyone's NORTH access up to
     * MANAGE, and must hand every other row back exactly as it is (SC-5).
     */
    @Test
    void aGranterGivesNoMoreThanTheyHoldAndLeavesWhatTheyCannotReachAsItIs() throws Exception {
        roleWith("NORTH_ADMIN", Privileges.USER_VIEW, Privileges.USER_MANAGE, Privileges.CUSTOMER_VIEW);
        User nadia = staff("nadia", "NORTH_ADMIN", north, AccessLevel.MANAGE, RecordScope.ALL);
        User tom = staff("tom", "CASHIER");
        String tomsGrants = "/api/users/" + tom.getId() + "/region-grants";
        send(put(tomsGrants), admin, grants(row(south, "READ", "ALL"))).andExpect(status().isOk());
        List<String> before = allGrants();

        // APPROVE is above her own MANAGE.
        send(put(tomsGrants), nadia, grants(row(south, "READ", "ALL"), row(north, "APPROVE", "ALL")))
                .andExpect(status().isForbidden());
        // Tom's SOUTH row is beyond her reach, so she may neither raise it, narrow it, nor drop it.
        send(put(tomsGrants), nadia, grants(row(south, "MANAGE", "ALL"))).andExpect(status().isForbidden());
        send(put(tomsGrants), nadia, grants(row(south, "READ", "BOOK"))).andExpect(status().isForbidden());
        send(put(tomsGrants), nadia, grants(row(north, "READ", "ALL"))).andExpect(status().isForbidden());
        // Nor may she create an account in SOUTH; the account is not created either.
        send(post("/api/users"), nadia, Map.of("username", "sol.south", "password", "Demo1234!",
                "roleId", role("CASHIER").getId(), "regionGrants", List.of(row(south, "READ", "BOOK"))))
                .andExpect(status().isForbidden());
        assertThat(allGrants()).isEqualTo(before);
        assertThat(userRepository.findByUsername("sol.south")).isEmpty();

        // Holding READ in SOUTH does not reach a MANAGE row there.
        send(put("/api/users/" + nadia.getId() + "/region-grants"), admin,
                grants(row(north, "MANAGE", "ALL"), row(south, "READ", "ALL"))).andExpect(status().isOk());
        send(put(tomsGrants), nadia, grants(row(south, "MANAGE", "ALL"))).andExpect(status().isForbidden());
        send(put("/api/users/" + nadia.getId() + "/region-grants"), admin,
                grants(row(north, "MANAGE", "ALL"))).andExpect(status().isOk());

        JsonNode kept = read(send(put(tomsGrants), nadia, grants(row(south, "READ", "ALL"), row(north, "MANAGE", "BOOK")))
                .andExpect(status().isOk()));
        assertThat(kept).extracting(n -> n.get("regionCode").asText() + ":" + n.get("level").asText()
                        + "/" + n.get("scope").asText() + ":" + n.get("grantedByUserId").asLong())
                .as("the SOUTH row came back untouched and stays as the administrator gave it")
                .containsExactly("NORTH:MANAGE/BOOK:" + nadia.getId(), "SOUTH:READ/ALL:" + admin.getId());

        // Bob holds NORTH only for his own book: he may shape book-only rows there, and nothing wider.
        User bob = staff("bob", "NORTH_ADMIN", north, AccessLevel.MANAGE, RecordScope.BOOK);
        send(put(tomsGrants), bob, grants(row(south, "READ", "ALL"), row(north, "MANAGE", "ALL")))
                .andExpect(status().isForbidden());
        send(put(tomsGrants), bob, grants(row(south, "READ", "ALL"), row(north, "READ", "BOOK")))
                .andExpect(status().isOk());
        assertThat(allGrants()).filteredOn(g -> g.startsWith("tom:"))
                .containsExactly("tom:NORTH:READ/BOOK", "tom:SOUTH:READ/ALL");
        send(put(tomsGrants), admin, grants(row(south, "READ", "ALL"), row(north, "READ", "ALL")))
                .andExpect(status().isOk());
        before = allGrants();
        // A row that reaches every record is beyond him even to take away.
        send(put(tomsGrants), bob, grants(row(south, "READ", "ALL"))).andExpect(status().isForbidden());
        assertThat(allGrants()).isEqualTo(before).contains("tom:NORTH:READ/ALL");
    }

    /** Giving APPROVE makes an approver, which only someone who may approve in that region may do (§4.6). */
    @Test
    void grantingApproveNeedsTheApprovalRightInThatRegion() throws Exception {
        Role granting = roleWith("GRANTER", Privileges.USER_VIEW, Privileges.USER_MANAGE, Privileges.CUSTOMER_VIEW);
        User gina = staff("gina", "GRANTER", north, AccessLevel.APPROVE, RecordScope.ALL);
        User tom = staff("tom", "CASHIER");
        String tomsGrants = "/api/users/" + tom.getId() + "/region-grants";
        List<String> before = allGrants();

        send(put(tomsGrants), gina, grants(row(north, "APPROVE", "ALL"))).andExpect(status().isForbidden());
        send(post("/api/users"), gina, Map.of("username", "amos.approver", "password", "Demo1234!",
                "roleId", role("CASHIER").getId(), "regionGrants", List.of(row(north, "APPROVE", "ALL"))))
                .andExpect(status().isForbidden());
        assertThat(allGrants()).isEqualTo(before);
        assertThat(userRepository.findByUsername("amos.approver")).isEmpty();

        send(put(tomsGrants), gina, grants(row(north, "MANAGE", "ALL"))).andExpect(status().isOk());

        granting.getPrivileges().add(privilegeRepository.findByName(Privileges.CHANGE_APPROVE).orElseThrow());
        roleRepository.save(granting);
        send(put(tomsGrants), gina, grants(row(north, "APPROVE", "ALL"))).andExpect(status().isOk());
        assertThat(allGrants()).filteredOn(g -> g.startsWith("tom:")).containsExactly("tom:NORTH:APPROVE/ALL");
    }

    @Test
    void aNewApproverIsAnnouncedToTheRegionsOtherApprovers() throws Exception {
        User abe = staff("abe", "ADMIN", north, AccessLevel.APPROVE, RecordScope.ALL);
        User ned = staff("ned", "ADMIN", south, AccessLevel.APPROVE, RecordScope.ALL);
        User reader = staff("rex", "ADMIN", north, AccessLevel.READ, RecordScope.ALL);
        User amy = staff("amy", "ADMIN", north, AccessLevel.MANAGE, RecordScope.ALL);

        send(put("/api/users/" + amy.getId() + "/region-grants"), admin,
                grants(row(north, "APPROVE", "ALL"))).andExpect(status().isOk());

        assertThat(approverNotices()).containsExactlyInAnyOrder("abe:AMY can now approve changes in North",
                "admin:AMY can now approve changes in North");
        assertThat(notificationRepository.findAll()).filteredOn(n -> n.getType().equals("APPROVER_ADDED"))
                .allSatisfy(n -> {
                    assertThat(n.getRegionId()).isEqualTo(north.getId());
                    assertThat(n.getTitle()).isEqualTo("New approver in North");
                })
                .noneMatch(n -> List.of(amy.getId(), ned.getId(), reader.getId()).contains(n.getUserId()));

        // Keeping APPROVE, or giving it to a role that cannot approve, announces nobody.
        notificationRepository.deleteAll();
        send(put("/api/users/" + amy.getId() + "/region-grants"), admin,
                grants(row(north, "APPROVE", "ALL"), row(south, "READ", "ALL"))).andExpect(status().isOk());
        User cal = staff("cal", "CASHIER");
        send(put("/api/users/" + cal.getId() + "/region-grants"), admin,
                grants(row(north, "APPROVE", "ALL"))).andExpect(status().isOk());
        assertThat(approverNotices()).isEmpty();

        // A new account made an approver is announced too.
        send(post("/api/users"), admin, Map.of("username", "ava.approver", "password", "Demo1234!",
                "roleId", role("ADMIN").getId(), "regionGrants", List.of(row(south, "APPROVE", "ALL"))))
                .andExpect(status().isOk());
        assertThat(approverNotices()).containsExactlyInAnyOrder("ned:ava.approver can now approve changes in South",
                "admin:ava.approver can now approve changes in South");
    }

    private List<String> approverNotices() {
        Map<Long, String> names = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        return notificationRepository.findAll().stream().filter(n -> n.getType().equals("APPROVER_ADDED"))
                .map(n -> names.get(n.getUserId()) + ":" + n.getMessage()).toList();
    }

    // ---- accounts -----------------------------------------------------------------------------------------

    @Test
    void anAccountHoldsOnlyTheRegionsItIsCreatedWith() throws Exception {
        Long roleId = role(DataSeeder.ROLE_SALES_POC).getId();

        JsonNode absent = read(send(post("/api/users"), admin,
                Map.of("username", "ann.absent", "password", "Demo1234!", "roleId", roleId))
                .andExpect(status().isOk()));
        JsonNode empty = read(send(post("/api/users"), admin,
                Map.of("username", "eve.empty", "password", "Demo1234!", "roleId", roleId, "regionGrants", List.of()))
                .andExpect(status().isOk()));
        JsonNode given = read(send(post("/api/users"), admin,
                Map.of("username", "gil.given", "password", "Demo1234!", "roleId", roleId,
                        "regionGrants", List.of(row(south, "MANAGE", "BOOK"), row(north, "READ", "BOOK"))))
                .andExpect(status().isOk()));

        assertThat(grantRepository.findWithRegionByUserId(absent.get("id").asLong())).isEmpty();
        assertThat(grantRepository.findWithRegionByUserId(empty.get("id").asLong())).isEmpty();
        assertThat(allGrants()).filteredOn(g -> g.startsWith("gil.given:"))
                .containsExactly("gil.given:NORTH:READ/BOOK", "gil.given:SOUTH:MANAGE/BOOK");
        assertThat(given.get("regionGrants")).extracting(n -> n.get("regionCode").asText())
                .containsExactly("NORTH", "SOUTH");
        JsonNode history = read(send(get("/api/audit").param("entityType", "USER")
                .param("entityId", given.get("id").asText()), admin, null).andExpect(status().isOk()));
        assertThat(actions(history)).containsExactly("USER_REGION_GRANTS_CHANGED");
        assertThat(history.get(0).get("changedByUsername").asText()).isEqualTo("admin");

        send(post("/api/users"), admin, Map.of("username", "dan.dup", "password", "Demo1234!", "roleId", roleId,
                "regionGrants", List.of(row(north, "READ", "ALL"), row(north, "READ", "BOOK"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.regionGrants").value("Region NORTH is listed more than once"));
        assertThat(userRepository.findByUsername("dan.dup")).isEmpty();
    }

    @Test
    void deletingAUserTakesItsGrantsWithIt() throws Exception {
        User leaving = staff("lee.leaving", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.BOOK);
        assertThat(allGrants()).filteredOn(g -> g.startsWith("lee.leaving:")).hasSize(2);

        send(delete("/api/users/" + leaving.getId()), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        assertThat(jdbc.queryForObject("select count(*) from user_region_grants where user_id = ?", Long.class,
                leaving.getId())).isZero();
    }

    // ---- privileges and history ---------------------------------------------------------------------------

    @Test
    void eachPrivilegeSaysTheLevelItNeedsInARegion() throws Exception {
        JsonNode privileges = read(send(get("/api/privileges"), admin, null).andExpect(status().isOk()));
        Map<String, JsonNode> byName = StreamSupport.stream(privileges.spliterator(), false)
                .collect(Collectors.toMap(p -> p.get("name").asText(), p -> p.get("regionLevel")));

        assertThat(byName.get(Privileges.CUSTOMER_VIEW).asText()).isEqualTo("READ");
        assertThat(byName.get(Privileges.SCOPE_OVERRIDE).asText()).isEqualTo("READ");
        assertThat(byName.get(Privileges.INVOICE_MANAGE).asText()).isEqualTo("MANAGE");
        assertThat(byName.get(Privileges.EMAIL_SEND).asText()).isEqualTo("MANAGE");
        for (String global : Privileges.GLOBAL) {
            assertThat(byName.get(global)).as(global).isNotNull();
            assertThat(byName.get(global).isNull()).as(global + " is global").isTrue();
        }
        assertThat(byName).hasSize(Privileges.ALL.size());
    }

    @Test
    void aDeletedCustomersHistoryIsMissingToThoseWhoCannotReachItsRegion() throws Exception {
        roleWith("HISTORY_READER", Privileges.CUSTOMER_VIEW, Privileges.AUDIT_VIEW);
        User northReader = staff("nat.reader", "HISTORY_READER", north, AccessLevel.READ, RecordScope.ALL);
        User southReader = staff("sue.reader", "HISTORY_READER", south, AccessLevel.READ, RecordScope.ALL);
        Customer gone = customer("Gone North Ltd", north);
        send(delete("/api/customers/" + gone.getId()), admin, null).andExpect(status().isOk());

        send(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", "" + gone.getId()),
                northReader, null).andExpect(status().isOk());
        send(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", "" + gone.getId()),
                southReader, null).andExpect(status().isNotFound());
    }

    /** Once the region itself is deleted nobody can hold it, so the history is read as the default region's. */
    @Test
    void aDeletedCustomersHistoryOutlivesItsRegion() throws Exception {
        roleWith("HISTORY_READER", Privileges.CUSTOMER_VIEW, Privileges.AUDIT_VIEW);
        User homeReader = staff("hal.reader", "HISTORY_READER", defaultRegion(), AccessLevel.READ, RecordScope.ALL);
        User northReader = staff("nat.reader", "HISTORY_READER", north, AccessLevel.READ, RecordScope.ALL);
        long east = createRegion("EAST", "East").get("id").asLong();
        Customer gone = customer("Gone East Ltd", regionRepository.findById(east).orElseThrow());
        send(delete("/api/customers/" + gone.getId()), admin, null).andExpect(status().isOk());
        send(delete("/api/regions/" + east), admin, null).andExpect(status().isNoContent());

        JsonNode history = read(send(get("/api/audit").param("entityType", "CUSTOMER")
                .param("entityId", "" + gone.getId()), admin, null).andExpect(status().isOk()));
        assertThat(actions(history)).contains("CUSTOMER_DELETED");
        send(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", "" + gone.getId()),
                homeReader, null).andExpect(status().isOk());
        send(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", "" + gone.getId()),
                northReader, null).andExpect(status().isNotFound());
    }
}
