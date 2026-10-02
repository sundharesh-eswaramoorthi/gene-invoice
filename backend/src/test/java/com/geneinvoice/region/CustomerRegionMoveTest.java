package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.history.VersionStore;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.DisputeVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.history.versions.TaskVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moving a customer to another region takes every record of it along, in one transaction (§5.6.8, D7): the rows,
 * their history (a new version of each at the move's instant, in the new region), the move log the approval gate
 * reads, and a history row on the customer. It asks for CUSTOMER_MANAGE in both regions, takes nothing from the
 * people who work the customer but names those who can no longer reach it, and any participant can refuse it.
 */
class CustomerRegionMoveTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired VersionStore versionStore;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    User admin;
    User sam;
    User cara;
    User colin;
    Region north;
    Region south;
    Product widget;
    Customer acme;
    List<Invoice> invoices;
    Payment payment;
    Long promiseId;
    Long disputeId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        // sam and colin work NORTH only; cara works both regions.
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        grant(sam, north, AccessLevel.READ, RecordScope.BOOK);
        grant(colin, north, AccessLevel.READ, RecordScope.BOOK);
        grant(cara, north, AccessLevel.READ, RecordScope.BOOK);
        grant(cara, south, AccessLevel.READ, RecordScope.BOOK);
        widget = product("Widget", "100.00");
        acme = customer("Acme Ltd", north);
        actAs(admin);
        invoices = List.of(invoice(), invoice());
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("40.00"),
                "Cash", null, List.of(invoices.get(0).getId()), colin.getId(), null));
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("60.00"),
                Today.date().plusDays(3), colin.getId(), null, List.of(invoices.get(1).getId()))).id();
        pocService.add(acme.getId(), PocType.COLLECTION, colin.getId(), true);
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), false);
        actAs(customerUser("acme.login", acme.getId()));
        disputeId = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                invoices.get(1).getId(), "Wrong quantity", null)).getId();
        SecurityContextHolder.clearContext();
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
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

    private ResultActions move(User caller, Object body) throws Exception {
        return mockMvc.perform(post("/api/customers/" + acme.getId() + "/move-region").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static Map<String, Object> to(Region r) {
        return Map.of("regionId", r.getId());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Every region-bearing row of the customer, by table and id. */
    private Map<String, Long> regions() {
        Map<String, Long> regions = new LinkedHashMap<>();
        for (String table : List.of("customers", "invoices", "payments", "payment_promises", "disputes")) {
            String key = table.equals("customers") ? "id" : "customer_id";
            jdbc.queryForList("select id, region_id from " + table + " where " + key + " = ? order by id",
                    acme.getId()).forEach(row -> regions.put(table + " " + row.get("ID"), (Long) row.get("REGION_ID")));
        }
        return regions;
    }

    /** What a refused move must leave exactly as it was. */
    private Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>(regions());
        state.put("customer version", jdbc.queryForObject("select version from customers where id = ?", Long.class,
                acme.getId()));
        state.put("invoice versions", jdbc.queryForList(
                "select version from invoices where customer_id = ? order by id", Long.class, acme.getId()));
        state.put("moves", customerRegionMoveRepository.count());
        state.put("audit", auditLogRepository.count());
        for (Class<? extends VersionRow> type : List.of(CustomerVersion.class, InvoiceVersion.class,
                PaymentVersion.class, PromiseVersion.class, DisputeVersion.class)) {
            state.put(type.getSimpleName(), historyTestSupport.allVersions(type).stream()
                    .map(v -> v.getVersionId() + "/" + v.getValidTo()).toList());
        }
        state.put("seats", jdbc.queryForList("select user_id from customer_pocs where customer_id = ? order by id",
                Long.class, acme.getId()));
        return state;
    }

    private List<AuditLog> regionChanges() {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", acme.getId()).stream()
                .filter(a -> a.getAction().equals("CUSTOMER_REGION_CHANGED")).toList();
    }

    @Test
    void theCustomerAndEveryRecordOfItMoveInOneGo() throws Exception {
        Instant at = MovableClock.SHARED.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        MovableClock.SHARED.set(at);
        Long customerVersion = customerRepository.findById(acme.getId()).orElseThrow().getVersion();
        Map<Long, Long> invoiceVersions = invoiceRepository.findAllById(invoices.stream().map(Invoice::getId)
                .toList()).stream().collect(Collectors.toMap(Invoice::getId, Invoice::getVersion));

        JsonNode result = read(move(admin, Map.of("regionId", south.getId(), "version", customerVersion,
                "reason", "Account moved to the South team")).andExpect(status().isOk()));

        assertThat(result.at("/customer/regionId").asLong()).isEqualTo(south.getId());
        assertThat(result.at("/customer/regionCode").asText()).isEqualTo("SOUTH");
        assertThat(result.at("/customer/version").asLong()).isEqualTo(customerVersion + 1);
        assertThat(result.at("/moved/invoices").asInt()).isEqualTo(2);
        assertThat(result.at("/moved/payments").asInt()).isEqualTo(1);
        assertThat(result.at("/moved/promises").asInt()).isEqualTo(1);
        assertThat(result.at("/moved/disputes").asInt()).isEqualTo(1);
        assertThat(regions()).hasSize(6).allSatisfy((row, region) -> assertThat(region).as(row)
                .isEqualTo(south.getId()));
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getVersion())
                .isEqualTo(customerVersion + 1);
        invoiceRepository.findAllById(invoiceVersions.keySet()).forEach(i -> assertThat(i.getVersion())
                .as("invoice " + i.getId()).isEqualTo(invoiceVersions.get(i.getId()) + 1));

        assertThat(customerRegionMoveRepository.findByCustomerIdOrderByMovedAtAscIdAsc(acme.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getFromRegionId()).isEqualTo(north.getId());
                    assertThat(m.getToRegionId()).isEqualTo(south.getId());
                    assertThat(m.getMovedAt()).isEqualTo(at);
                    assertThat(m.getMovedByUserId()).isEqualTo(admin.getId());
                });
        assertThat(regionChanges()).singleElement().satisfies(a -> {
            assertThat(a.getRegionId()).isEqualTo(south.getId());
            assertThat(a.getReason()).isEqualTo("Account moved to the South team");
            assertThat(a.getChangedByUserId()).isEqualTo(admin.getId());
            JsonNode before = objectMapper.readTree(a.getBeforeJson());
            JsonNode after = objectMapper.readTree(a.getAfterJson());
            assertThat(before.get("regionId").asLong()).isEqualTo(north.getId());
            assertThat(before.get("regionCode").asText()).isEqualTo("NORTH");
            assertThat(after.get("regionId").asLong()).isEqualTo(south.getId());
            assertThat(after.get("regionCode").asText()).isEqualTo("SOUTH");
            assertThat(after.at("/moved/invoices").asInt()).isEqualTo(2);
        });
        assertThat(moveParticipant.before()).containsExactly(
                new TestMoveParticipant.Call(acme.getId(), north.getId(), south.getId()));
        assertThat(moveParticipant.after()).containsExactly(
                new TestMoveParticipant.Call(acme.getId(), north.getId(), south.getId()));
        mockMvc.perform(get("/api/customers/" + acme.getId()).with(as(admin)))
                .andExpect(jsonPath("$.regionId").value(south.getId()));
    }

    /**
     * Every moved row gets a new version at the move's instant, in the new region, written by one transaction;
     * the version before it ends there, so the customer's records read as NORTH's up to the move and SOUTH's after.
     */
    @Test
    void historyShowsTheOldRegionBeforeTheMoveAndTheNewOneFromIt() throws Exception {
        Instant at = MovableClock.SHARED.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        MovableClock.SHARED.set(at);

        move(admin, to(south)).andExpect(status().isOk());

        Map<Class<? extends VersionRow>, List<Long>> moved = Map.of(
                CustomerVersion.class, List.of(acme.getId()),
                InvoiceVersion.class, invoices.stream().map(Invoice::getId).toList(),
                PaymentVersion.class, List.of(payment.getId()),
                PromiseVersion.class, List.of(promiseId),
                DisputeVersion.class, List.of(disputeId));
        List<String> txIds = new ArrayList<>();
        moved.forEach((type, ids) -> {
            for (Long id : ids) {
                List<? extends VersionRow> versions = historyTestSupport.versionsOf(type, id);
                VersionRow before = versions.get(versions.size() - 2);
                VersionRow after = versions.get(versions.size() - 1);
                String what = type.getSimpleName() + " " + id;
                assertThat(regionOf(before)).as(what).isEqualTo(north.getId());
                assertThat(before.getValidTo()).as(what).isEqualTo(at);
                assertThat(regionOf(after)).as(what).isEqualTo(south.getId());
                assertThat(after.getValidFrom()).as(what).isEqualTo(at);
                assertThat(after.isOpen()).as(what).isTrue();
                assertThat(after.getActorUserId()).as(what).isEqualTo(admin.getId());
                txIds.add(after.getTxId());

                Instant justBefore = at.minus(1, ChronoUnit.MICROS);
                assertThat(validAt(type, id, justBefore)).as(what + " as of just before the move").singleElement()
                        .satisfies(v -> assertThat(regionOf(v)).isEqualTo(north.getId()));
                assertThat(validAt(type, id, at)).as(what + " as of the move").singleElement()
                        .satisfies(v -> assertThat(regionOf(v)).isEqualTo(south.getId()));
            }
        });
        assertThat(new HashSet<>(txIds)).as("one transaction").hasSize(1);
    }

    /**
     * A clock finer than the database keeps: the move is logged at the instant its versions start, to the
     * microsecond, not at the clock's nanoseconds rounded by the database.
     */
    @Test
    void theMoveIsLoggedAtTheInstantItsVersionsStart() throws Exception {
        Instant at = MovableClock.SHARED.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS)
                .plusNanos(999);
        MovableClock.SHARED.set(at);

        move(admin, to(south)).andExpect(status().isOk());

        Instant versionStart = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()).stream()
                .filter(VersionRow::isOpen).map(VersionRow::getValidFrom).findFirst().orElseThrow();
        assertThat(versionStart).isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
        assertThat(customerRegionMoveRepository.findByCustomerIdOrderByMovedAtAscIdAsc(acme.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getMovedAt().getNano() % 1_000).as("no sub-microsecond nanos").isZero();
                    assertThat(m.getMovedAt()).isEqualTo(versionStart);
                });
    }

    /** The version of a record valid at {@code p}: what an as-of read at that instant answers from. */
    private List<VersionRow> validAt(Class<? extends VersionRow> type, Long id, Instant p) {
        return new ArrayList<>(tx.execute(s -> versionStore.validAt(type, List.of(id), p)));
    }

    private static Long regionOf(VersionRow v) {
        if (v instanceof CustomerVersion c) return c.getRegionId();
        if (v instanceof InvoiceVersion i) return i.getRegionId();
        if (v instanceof PaymentVersion p) return p.getRegionId();
        if (v instanceof PromiseVersion p) return p.getRegionId();
        if (v instanceof DisputeVersion d) return d.getRegionId();
        throw new IllegalArgumentException(v.getClass().getSimpleName());
    }

    /** Nobody loses a seat or a record; the mover is told who can no longer reach the customer. */
    @Test
    void peopleWhoCannotReachTheNewRegionAreNamedAndKeepWhatTheyHold() throws Exception {
        List<Long> seats = jdbc.queryForList("select user_id from customer_pocs where customer_id = ? order by id",
                Long.class, acme.getId());

        JsonNode result = read(move(admin, to(south)).andExpect(status().isOk()));

        assertThat(result.get("warnings")).extracting(JsonNode::asText).containsExactly(
                "colin.collections is Collection POC of Acme Ltd but has no access to South",
                "sam.sales is Sales POC on 2 invoices but has no access to South",
                "colin.collections is Collection POC on 1 payment but has no access to South",
                "colin.collections is Collection POC on 1 promise but has no access to South");
        assertThat(jdbc.queryForList("select user_id from customer_pocs where customer_id = ? order by id",
                Long.class, acme.getId())).isEqualTo(seats);
        invoiceRepository.findAllById(invoices.stream().map(Invoice::getId).toList())
                .forEach(i -> assertThat(i.getSalesPoc().getId()).isEqualTo(sam.getId()));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(colin.getId());

        grant(sam, south, AccessLevel.READ, RecordScope.BOOK);
        grant(colin, south, AccessLevel.READ, RecordScope.BOOK);
        JsonNode back = read(move(admin, to(north)).andExpect(status().isOk()));
        assertThat(back.get("warnings")).as("everyone reaches NORTH").isEmpty();
    }

    @Test
    void aMoveAsksForCustomerManageInBothRegionsAndChangesNothingWhenRefused() throws Exception {
        Region west = region("WEST");
        Region east = region("EAST");
        grant(admin, east, AccessLevel.APPROVE, RecordScope.ALL);
        east.setActive(false);
        regionRepository.save(east);
        User readsNorth = staff("rita.reader", "ADMIN", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        User readsSouth = staff("nora.manager", "ADMIN", north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.READ, RecordScope.ALL);
        User southOnly = staff("sue.south", "ADMIN", south, AccessLevel.MANAGE, RecordScope.ALL);
        Long version = customerRepository.findById(acme.getId()).orElseThrow().getVersion();
        Map<String, Object> before = state();

        move(readsNorth, to(south)).andExpect(status().isForbidden());
        move(readsSouth, to(south)).andExpect(status().isForbidden());
        move(southOnly, to(south)).andExpect(status().isNotFound());
        move(admin, to(west)).andExpect(status().isNotFound());
        move(admin, Map.of("regionId", 987_654_321L)).andExpect(status().isNotFound());
        move(admin, to(east)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Region East is inactive"));
        move(admin, to(north)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The customer is already in North"));
        move(admin, Map.of("regionId", south.getId(), "version", version - 1)).andExpect(status().isConflict());
        move(admin, Map.of("reason", "Nowhere")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.regionId").exists());
        move(admin, Map.of("regionId", south.getId(), "reason", "x".repeat(501)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/move-region").with(as(admin))
                        .param("asOf", Today.liveDate().minusDays(1).toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(to(south))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_READ_ONLY"));
        // A sales POC who sees NORTH but whose role may not manage customers at all.
        User salesInBoth = staff("sal.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        move(salesInBoth, to(south)).andExpect(status().isForbidden());

        assertThat(state()).isEqualTo(before);
        assertThat(moveParticipant.after()).isEmpty();
        move(admin, Map.of("regionId", south.getId(), "version", version)).andExpect(status().isOk());
    }

    /** A customer login may edit its own customer here, but it holds no other region to move it to. */
    @Test
    void aCustomerLoginCannotMoveItsCustomer() throws Exception {
        Role selfEditor = roleRepository.findByName("CUSTOMER_SELF_MOVER").orElseGet(() -> Role.builder()
                .name("CUSTOMER_SELF_MOVER").description("Customer login that may edit itself").build());
        selfEditor.setPrivileges(new HashSet<>(Stream.concat(role("CUSTOMER").getPrivileges().stream(),
                Stream.of(privilegeRepository.findByName(Privileges.CUSTOMER_MANAGE).orElseThrow())).toList()));
        roleRepository.save(selfEditor);
        User login = userRepository.findByUsername("acme.login").orElseThrow();
        login.setRole(selfEditor);
        userRepository.save(login);
        Map<String, Object> before = state();

        move(login, to(south)).andExpect(status().isNotFound());

        assertThat(state()).isEqualTo(before);
    }

    /** Someone who works NORTH only can no longer see any of it once it is in SOUTH. */
    @Test
    void aNorthOnlyUserLosesSightOfTheCustomerOnceItMoves() throws Exception {
        User nick = staff("nick.north", "ADMIN", north, AccessLevel.READ, RecordScope.ALL);
        List<String> paths = List.of("/api/customers/" + acme.getId(), "/api/invoices/" + invoices.get(0).getId(),
                "/api/payments/" + payment.getId(), "/api/promises/" + promiseId, "/api/disputes/" + disputeId);
        for (String path : paths) mockMvc.perform(get(path).with(as(nick))).andExpect(status().isOk());

        move(admin, to(south)).andExpect(status().isOk());

        for (String path : paths) mockMvc.perform(get(path).with(as(nick))).andExpect(status().isNotFound());
        assertThat(read(mockMvc.perform(get("/api/invoices").with(as(nick))).andExpect(status().isOk()))
                .get("totalElements").asLong()).isZero();
    }

    /** A participant that refuses the move (as open approvals will, BE-15) leaves everything as it was. */
    @Test
    void aParticipantsRefusalRollsTheWholeMoveBack() throws Exception {
        moveParticipant.veto("Acme Ltd has 2 changes awaiting approval; they must be decided first");
        Map<String, Object> before = state();

        move(admin, to(south)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Acme Ltd has 2 changes awaiting approval; they must be decided first"));

        assertThat(state()).isEqualTo(before);
        assertThat(moveParticipant.before()).hasSize(1);
        assertThat(moveParticipant.after()).isEmpty();
    }

    /**
     * CF-16: a participant that fails once everything is written (the native updates, the history touches, the move
     * row and the other participants' own work) rolls all of it back too.
     */
    @Test
    void aParticipantFailingAfterEverythingIsWrittenRollsTheWholeMoveBack() throws Exception {
        long task = objectMapper.readTree(mockMvc.perform(post("/api/tasks").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("entityType", "CUSTOMER", "entityId", acme.getId(),
                                "title", "Renew the contract"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        // A cancel waiting on the customer, which the move closes, since admin approves in NORTH.
        approvalThresholdRepository.save(ApprovalThreshold.builder().region(north)
                .operation(ApprovalOperation.INVOICE_CANCEL).amount(BigDecimal.ONE).windowHours(0)
                .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        User maker = staff("mo.maker", "ADMIN", north, AccessLevel.MANAGE, RecordScope.ALL);
        actAs(admin);
        Invoice unpaid = invoice();
        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/api/invoices/" + unpaid.getId() + "/cancel").with(as(maker)))
                .andExpect(status().isAccepted());
        PendingChange waiting = pendingChangeRepository.findByOutstandingKey("INVOICE:" + unpaid.getId())
                .orElseThrow();
        Map<String, Object> before = withSideEffects(state(), task, waiting.getId());
        List<String> seenWhileMoving = new ArrayList<>();
        moveParticipant.failAfter("The ledger refused the move", () -> {
            // Inside the move: everything is written, the other participants' work included.
            seenWhileMoving.add("customer in " + jdbc.queryForObject(
                    "select region_id from customers where id = ?", Long.class, acme.getId()));
            seenWhileMoving.add("task in " + jdbc.queryForObject(
                    "select region_id from tasks where id = ?", Long.class, task));
            seenWhileMoving.add("change " + pendingChangeRepository.findById(waiting.getId()).orElseThrow()
                    .getStatus());
            seenWhileMoving.add("moves " + customerRegionMoveRepository.count());
        });

        move(admin, to(south)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The ledger refused the move"));

        assertThat(seenWhileMoving).containsExactly("customer in " + south.getId(), "task in " + south.getId(),
                "change CLOSED", "moves 1");
        assertThat(moveParticipant.after()).hasSize(1);
        assertThat(withSideEffects(state(), task, waiting.getId())).isEqualTo(before);
        assertThat(regions()).allSatisfy((row, region) -> assertThat(region).as(row).isEqualTo(north.getId()));
        assertThat(customerRegionMoveRepository.count()).isZero();
        assertThat(regionChanges()).isEmpty();

        moveParticipant.reset();
        move(admin, to(south)).andExpect(status().isOk());
    }

    /** {@link #state()} and what the other participants do: the task's region and history, the waiting change. */
    private Map<String, Object> withSideEffects(Map<String, Object> state, long taskId, Long pendingChangeId) {
        state.put("task region", jdbc.queryForObject("select region_id from tasks where id = ?", Long.class, taskId));
        state.put("task versions", historyTestSupport.versionsOf(TaskVersion.class, taskId).stream()
                .map(v -> v.getVersionId() + "/" + v.getValidTo()).toList());
        PendingChange pc = pendingChangeRepository.findById(pendingChangeId).orElseThrow();
        state.put("change", pc.getStatus() + "/" + pc.getOutstandingKey() + "/" + pc.getDecidedAt());
        state.put("notifications", notificationRepository.count());
        state.put("automation events", automationEventRepository.count());
        return state;
    }

    @Test
    void whatEachParticipantMovedIsCountedUnderItsName() throws Exception {
        moveParticipant.reportRows(3);

        JsonNode result = read(move(admin, to(south)).andExpect(status().isOk()));

        assertThat(result.at("/moved/other/TestMoveParticipant").asInt()).isEqualTo(3);
        // Automation publishes the move and moves no rows of its own (M1).
        assertThat(result.at("/moved/other/AutomationMoveParticipant").isInt()).isTrue();
        assertThat(result.at("/moved/other/AutomationMoveParticipant").asInt()).isZero();
        // Tasks move their own rows (BE-12); Acme has none here.
        assertThat(result.at("/moved/other/TaskMoveParticipant").asInt()).isZero();
        assertThat(result.at("/moved/other/PendingChangeMoveParticipant").asInt()).as("nothing was waiting").isZero();
        assertThat(result.at("/moved/other").size()).as("the participants that exist so far").isEqualTo(4);
    }

    /**
     * A customer's tasks move with it, and are answered by region as they were (PC-16): asked about NORTH as of the
     * day before the move, the task is there and SOUTH's list is empty; asked today, the other way round. Its
     * history changes region at the move's instant.
     */
    @Test
    void aCustomersTasksMoveWithItAndAreFoundInTheRegionTheyWereInThen() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 3, 1);
        at(d1);
        Customer initech = customer("Initech", north);
        long task = objectMapper.readTree(mockMvc.perform(post("/api/tasks").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("entityType", "CUSTOMER", "entityId", initech.getId(),
                                "title", "Renew the contract"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();

        at(d1.plusDays(1));
        Instant movedAt = MovableClock.SHARED.instant().truncatedTo(ChronoUnit.MICROS);
        JsonNode result = read(mockMvc.perform(post("/api/customers/" + initech.getId() + "/move-region")
                        .with(as(admin)).contentType(MediaType.APPLICATION_JSON).content(json(to(south))))
                .andExpect(status().isOk()));
        assertThat(result.at("/moved/other/TaskMoveParticipant").asInt()).isEqualTo(1);
        assertThat(taskRepository.findById(task).orElseThrow().getRegion().getId()).isEqualTo(south.getId());

        at(d1.plusDays(2));
        String before = d1.toString();
        assertThat(taskIds(north, before)).containsExactly(task);
        assertThat(taskIds(south, before)).isEmpty();
        assertThat(taskIds(north, null)).isEmpty();
        assertThat(taskIds(south, null)).containsExactly(task);

        List<TaskVersion> versions = historyTestSupport.versionsOf(TaskVersion.class, task);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getRegionId()).isEqualTo(north.getId());
        assertThat(versions.get(0).getValidTo()).isEqualTo(movedAt);
        assertThat(versions.get(1).getRegionId()).isEqualTo(south.getId());
        assertThat(versions.get(1).getValidFrom()).isEqualTo(movedAt);
        assertThat(versions.get(1).isOpen()).isTrue();
    }

    private static void at(LocalDate day) {
        MovableClock.SHARED.set(day.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
    }

    /** The ids on the task list asked about one region, live or as of a day. */
    private List<Long> taskIds(Region region, String asOf) throws Exception {
        var request = get("/api/tasks").with(as(admin)).param("regionIds", region.getId().toString());
        if (asOf != null) request.param("asOf", asOf);
        JsonNode page = read(mockMvc.perform(request).andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    /** A copy of a promise read before the move and saved after it never puts the old region back (F3). */
    @Test
    void aPromiseSavedFromACopyReadBeforeTheMoveKeepsTheNewRegion() throws Exception {
        PaymentPromise stale = promiseRepository.findById(promiseId).orElseThrow();

        move(admin, to(south)).andExpect(status().isOk());
        stale.setNotes("Saved from an old copy");
        promiseRepository.save(stale);

        tx.executeWithoutResult(s -> {
            PaymentPromise saved = promiseRepository.findById(promiseId).orElseThrow();
            assertThat(saved.getNotes()).isEqualTo("Saved from an old copy");
            assertThat(saved.getRegion().getId()).isEqualTo(south.getId());
        });
        assertThat(historyTestSupport.versionsOf(PromiseVersion.class, promiseId)).last().satisfies(v -> {
            assertThat(v.getNotes()).isEqualTo("Saved from an old copy");
            assertThat(v.getRegionId()).isEqualTo(south.getId());
        });
    }

    /**
     * After the move a broken promise is not told to its Collection POC, who cannot reach SOUTH, but to SOUTH's
     * promise managers (SC-17).
     */
    @Test
    void aBrokenPromiseAfterTheMoveGoesToTheNewRegionsManagers() throws Exception {
        User ada = staff("ada.admin", "ADMIN", south, AccessLevel.MANAGE, RecordScope.ALL);

        move(admin, to(south)).andExpect(status().isOk());
        actAs(admin);
        promiseService.override(promiseId, PromiseStatus.BROKEN, "Refused to pay");

        Map<Long, Long> told = new HashMap<>();
        notificationRepository.findAll().stream().filter(n -> n.getType().equals("PROMISE_BROKEN"))
                .forEach(n -> told.put(n.getUserId(), n.getRegionId()));
        assertThat(told).doesNotContainKey(colin.getId()).containsEntry(ada.getId(), south.getId());
    }

    /**
     * SC-7 (BE-15): a change of a promise held while the customer moves waits in the region the promise is in by the
     * time it is held, never the one it was in before. The move holds the customer when the change arrives; the
     * change waits for it, then reads the promise's region under the customer's lock.
     */
    @Test
    void aPromiseChangeHeldWhileTheCustomerMovesWaitsInTheNewRegion() throws Exception {
        for (Region r : List.of(north, south)) {
            approvalThresholdRepository.save(ApprovalThreshold.builder().region(r)
                    .operation(ApprovalOperation.PROMISE_UPDATE).amount(BigDecimal.ONE).windowHours(0)
                    .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        }
        // Manages promises in both regions, approves nothing.
        User maker = staff("mo.maker", "ADMIN", north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        Map<String, Object> change = new HashMap<>();
        change.put("amount", new BigDecimal("30.00"));
        change.put("promisedDate", Today.date().plusDays(3).toString());
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            @SuppressWarnings("unchecked")
            Future<Integer>[] held = new Future[1];
            historyFaults.onNextSnapshotOf("CUSTOMER", acme.getId(), () -> {
                held[0] = other.submit(() -> mockMvc.perform(put("/api/promises/" + promiseId).with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content(json(change)))
                        .andReturn().getResponse().getStatus());
                awaitSomeoneWaitingOnALock(held[0]);
            });
            move(admin, to(south)).andExpect(status().isOk());
            assertThat(held[0].get(10, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            other.shutdownNow();
        }

        PendingChange pc = pendingChangeRepository.findByOutstandingKey("PROMISE:" + promiseId).orElseThrow();
        assertThat(pc.getRegionId()).isEqualTo(south.getId());
        List<Long> strays = jdbc.queryForList("""
                select p.id from pending_changes p join customers c on c.id = p.customer_id
                where p.status = 'PENDING' and p.region_id <> c.region_id""", Long.class);
        assertThat(strays).as("no change waits outside its customer's region").isEmpty();
        assertThat(promiseRepository.findById(promiseId).orElseThrow().getAmount()).isEqualByComparingTo("60.00");
    }

    /** Until another session is seen waiting on a lock; its finishing first means it never waited. */
    private void awaitSomeoneWaitingOnALock(Future<?> running) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) throw new AssertionError("finished without waiting for the customer's lock");
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("was never seen waiting for the customer's lock");
    }
}
