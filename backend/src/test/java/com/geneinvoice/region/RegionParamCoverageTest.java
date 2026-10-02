package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.common.scope.ViewScopeInventoryTest;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.email.RecipientField;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.task.TaskStatus;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every set endpoint answers "which regions am I asking about" (B1, critic R1): with a NORTH and a SOUTH
 * customer, each with an invoice, a payment, a promise and a dispute, asking for SOUTH gives SOUTH's rows only
 * and says so. One row per endpoint in {@link ViewScopeInventoryTest#SETS}; an endpoint whose narrowing
 * belongs to a later slice is named in {@link #PENDING_ROWS} until then.
 */
class RegionParamCoverageTest extends IntegrationTestBase {

    /** Endpoint → the slice that narrows it and adds its row (CF-12). None is pending until a slice adds one. */
    static final Map<String, String> PENDING_ROWS = Map.of();

    @FunctionalInterface
    interface Row {
        void check(RegionParamCoverageTest t) throws Exception;
    }

    static final Map<String, Row> ROWS = new LinkedHashMap<>();

    static {
        ROWS.put("GET /api/customers", t -> t.list("/api/customers", "name"));
        ROWS.put("GET /api/invoices", t -> t.list("/api/invoices", "customerName"));
        ROWS.put("GET /api/payments", t -> t.list("/api/payments", "customerName"));
        ROWS.put("GET /api/promises", t -> t.list("/api/promises", "customerName"));
        ROWS.put("GET /api/disputes", t -> t.list("/api/disputes", "customerName"));
        ROWS.put("GET /api/customers/summary", t -> t.summary("/api/customers/summary", "count", 1));
        ROWS.put("GET /api/invoices/summary", t -> t.summary("/api/invoices/summary", "count", 2));
        ROWS.put("GET /api/payments/summary", t -> t.summary("/api/payments/summary", "count", 1));
        ROWS.put("GET /api/promises/summary", t -> t.summary("/api/promises/summary", "total", 1));
        ROWS.put("POST /api/customers/export", t -> t.export("/api/customers/export"));
        ROWS.put("POST /api/invoices/export", t -> t.export("/api/invoices/export"));
        ROWS.put("POST /api/payments/export", t -> t.export("/api/payments/export"));
        ROWS.put("POST /api/promises/export", t -> t.export("/api/promises/export"));
        ROWS.put("POST /api/disputes/export", t -> t.export("/api/disputes/export"));
        ROWS.put("POST /api/customers/bulk", RegionParamCoverageTest::customersBulk);
        ROWS.put("POST /api/invoices/bulk", RegionParamCoverageTest::invoicesBulk);
        ROWS.put("POST /api/payments/bulk", RegionParamCoverageTest::paymentsBulk);
        ROWS.put("POST /api/promises/bulk", RegionParamCoverageTest::promisesBulk);
        ROWS.put("GET /api/dashboard/billed-by-month", RegionParamCoverageTest::billedByMonth);
        ROWS.put("GET /api/dashboard/outstanding-by-age", RegionParamCoverageTest::outstandingByAge);
        ROWS.put("GET /api/dashboard/top-outstanding-customers", RegionParamCoverageTest::topOutstanding);
        ROWS.put("GET /api/dashboard/collected-by-month", RegionParamCoverageTest::collectedByMonth);
        ROWS.put("GET /api/dashboard/top-paying-customers", RegionParamCoverageTest::topPaying);
        ROWS.put("POST /api/emails/bulk", RegionParamCoverageTest::emailBulk);
        ROWS.put("GET /api/inbox", RegionParamCoverageTest::inbox);
        ROWS.put("GET /api/inbox/unread-count", RegionParamCoverageTest::inboxUnreadCount);
        ROWS.put("POST /api/inbox/bulk", RegionParamCoverageTest::inboxBulk);
        ROWS.put("GET /api/pocs/assignable", RegionParamCoverageTest::assignablePocs);
        ROWS.put("GET /api/pocs/my-scope", RegionParamCoverageTest::myScope);
        ROWS.put("GET /api/notifications", RegionParamCoverageTest::notifications);
        ROWS.put("GET /api/notifications/unread-count", RegionParamCoverageTest::notificationsUnreadCount);
        ROWS.put("POST /api/notifications/bulk", RegionParamCoverageTest::notificationsBulk);
        ROWS.put("GET /api/tasks", t -> t.list("/api/tasks", "customerName"));
        ROWS.put("GET /api/tasks/summary", t -> t.summary("/api/tasks/summary", "count", 1));
        ROWS.put("GET /api/tasks/mine", t -> t.list("/api/tasks/mine", "customerName"));
        ROWS.put("GET /api/tasks/mine/summary", t -> t.summary("/api/tasks/mine/summary", "count", 1));
        ROWS.put("GET /api/tasks/mine/count", t -> t.summary("/api/tasks/mine/count", "count", 1));
        ROWS.put("POST /api/tasks/bulk", RegionParamCoverageTest::tasksBulk);
        ROWS.put("POST /api/tasks/export", t -> t.export("/api/tasks/export"));
        ROWS.put("GET /api/assignees/people", RegionParamCoverageTest::assigneePeople);
        ROWS.put("GET /api/approvals", RegionParamCoverageTest::approvals);
        ROWS.put("GET /api/approvals/summary", RegionParamCoverageTest::approvalsSummary);
        ROWS.put("GET /api/approvals/awaiting-me/count", RegionParamCoverageTest::approvalsAwaitingMe);
        ROWS.put("POST /api/approvals/export", RegionParamCoverageTest::approvalsExport);
        ROWS.put("GET /api/automation/rules", RegionParamCoverageTest::rules);
        ROWS.put("GET /api/automation/rules/summary", RegionParamCoverageTest::rulesSummary);
        ROWS.put("POST /api/automation/rules/preview", RegionParamCoverageTest::rulePreview);
        ROWS.put("GET /api/automation/rules/{id}/preview", RegionParamCoverageTest::savedRulePreview);
        ROWS.put("GET /api/automation/rules/{id}/matches", RegionParamCoverageTest::ruleMatches);
    }

    static Stream<String> rows() {
        return ROWS.keySet().stream();
    }

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired TaskService taskService;
    @Autowired JdbcTemplate jdbc;

    User admin;
    User sales;
    User collections;
    Region north;
    Region south;
    Product widget;
    /** Per region code: its customer, its paid invoice, an unpaid one, its payment, promise and dispute, and a task. */
    Map<String, Fixture> fixtures = new HashMap<>();

    record Fixture(Customer customer, Invoice paid, Invoice unpaid, Payment payment,
                   PromiseDtos.PromiseDto promise, Long disputeId, Long taskId) {}

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        // A POC is assignable only where they hold a grant (§5.6.7): these two work in both regions.
        for (Region r : List.of(north, south)) {
            grant(sales, r, AccessLevel.READ, RecordScope.BOOK);
            grant(collections, r, AccessLevel.READ, RecordScope.BOOK);
        }
        widget = product("Widget", "100.00");
        // Different figures in each region, so a total that took in NORTH could never pass for SOUTH's.
        fixtures.put("NORTH", fixture("North Co", north, 1, "40.00"));
        fixtures.put("SOUTH", fixture("South Co", south, 3, "70.00"));
        actAs(admin);
    }

    private Fixture fixture(String name, Region region, int quantity, String paid) {
        actAs(admin);
        Customer c = customer(name, region);
        Invoice paidInvoice = invoice(c, quantity);
        Invoice unpaid = invoice(c, quantity);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal(paid), "Cash", null, List.of(paidInvoice.getId()), collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(c.getId(),
                new BigDecimal("25.00"), Today.date().plusDays(3), collections.getId(), null, List.of()));
        actAs(customerUser(name.toLowerCase().replace(' ', '.') + ".login", c.getId()));
        Long dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, unpaid.getId(), "Wrong quantity", null)).getId();
        actAs(admin);
        // Assigned to admin, so it is on admin's "my tasks" too.
        Long task = taskService.create(new TaskDtos.CreateTaskRequest(TaskEntityType.CUSTOMER, c.getId(),
                "Call " + name, null, null, List.of(EmailDtos.EmailToken.user(admin.getId())))).getId();
        return new Fixture(c, paidInvoice, unpaid, payment, promise, dispute, task);
    }

    private Invoice invoice(Customer c, int quantity) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, null))));
    }

    private Fixture north() {
        return fixtures.get("NORTH");
    }

    private Fixture south() {
        return fixtures.get("SOUTH");
    }

    @Test
    void theRowsCoverEverySetEndpoint() {
        assertThat(PENDING_ROWS.keySet()).as("an endpoint has a row or is pending, never both")
                .noneMatch(ROWS::containsKey);
        Set<String> covered = new TreeSet<>(ROWS.keySet());
        covered.addAll(PENDING_ROWS.keySet());
        assertThat(covered).isEqualTo(new TreeSet<>(ViewScopeInventoryTest.SETS));
    }

    /** Each regional list offers its region as a column: filtered by id, sorted and shown by name (§6.5). */
    @Test
    void everyRegionalListHasTheRegionAsAColumn() throws Exception {
        for (String entity : List.of("customers", "invoices", "payments", "promises", "disputes")) {
            JsonNode columns = getAll("/api/table-schemas/" + entity).get("columns");
            JsonNode regionId = columns.findParents("name").stream()
                    .filter(c -> c.get("name").asText().equals("regionId")).findFirst().orElseThrow();
            JsonNode regionName = columns.findParents("name").stream()
                    .filter(c -> c.get("name").asText().equals("regionName")).findFirst().orElseThrow();
            assertThat(regionId.get("type").asText()).as(entity).isEqualTo("REFERENCE");
            assertThat(regionId.get("referenceKind").asText()).as(entity).isEqualTo("region");
            assertThat(regionName.get("sortable").asBoolean()).as(entity).isTrue();
        }
        JsonNode filtered = read(mockMvc.perform(get("/api/invoices").with(as(admin))
                .param("filter", "regionId:eq:" + north.getId())));
        assertThat(filtered.get("content")).extracting(n -> n.get("customerName").asText()).containsOnly("North Co");
        JsonNode sorted = read(mockMvc.perform(get("/api/customers").with(as(admin))
                .param("sort", "regionName,desc")));
        assertThat(sorted.get("content")).extracting(n -> n.get("regionName").asText())
                .containsExactly("South", "North");
    }

    /** Email bulk takes only the records the caller may also email about: EMAIL_SEND needs MANAGE (§5.6.5). */
    @Test
    void emailBulkSelectsOnlyTheRegionsWhereTheCallerMaySend() throws Exception {
        User rita = user("rita", "CASHIER");
        grantRepository.deleteAllOfUser(rita.getId());
        grant(rita, north, AccessLevel.READ, RecordScope.ALL);
        grant(rita, south, AccessLevel.MANAGE, RecordScope.ALL);
        Map<String, Object> params = Map.of(
                "entityType", "INVOICE",
                "to", List.of(Map.of("type", "USER", "userId", admin.getId())),
                "subject", "Your invoice",
                "body", "Hello");

        JsonNode result = read(mockMvc.perform(post("/api/emails/bulk").with(as(rita))
                .contentType(MediaType.APPLICATION_JSON).content(json(allMatching("SEND_EMAIL", params)))));

        assertThat(result.get("requested").asInt()).as("NORTH is only read, so its invoices are not offered")
                .isEqualTo(2);
        assertThat(emailRepository.findAll()).extracting(Email::getEntityId)
                .containsExactlyInAnyOrder(south().paid().getId(), south().unpaid().getId());
    }

    /**
     * "Mark all read" is a write to the caller's own notices, not a set, but it marks only what the narrowed bell
     * shows: SOUTH's notice and the one about no region, never an unseen NORTH one.
     */
    @Test
    void markingAllReadTakesInOnlyTheRegionsAskedAbout() throws Exception {
        notificationRepository.deleteAll();
        Map<String, Long> rows = threeNotifications();
        JsonNode result = read(mockMvc.perform(southOnly(post("/api/notifications/mark-all-read"))));
        assertThat(result.get("updated").asInt()).isEqualTo(2);
        assertThat(notificationRepository.findById(rows.get("NORTH")).orElseThrow().isRead()).isFalse();
        assertThat(notificationRepository.findById(rows.get("SOUTH")).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(rows.get("NONE")).orElseThrow().isRead()).isTrue();

        Region west = region("WEST");
        Long westOnly = notification("West invoice", west.getId());
        Long noRegion = notification("Reconnect Gmail again", null);
        JsonNode nowhere = read(mockMvc.perform(post("/api/notifications/mark-all-read").with(as(admin))
                .param("regionIds", west.getId().toString())));
        assertThat(nowhere.get("updated").asInt()).as("admin holds no WEST, so only the notice about no region")
                .isEqualTo(1);
        assertThat(notificationRepository.findById(westOnly).orElseThrow().isRead()).isFalse();
        assertThat(notificationRepository.findById(noRegion).orElseThrow().isRead()).isTrue();

        JsonNode all = read(mockMvc.perform(post("/api/notifications/mark-all-read").with(as(admin))));
        assertThat(all.get("updated").asInt()).as("unnarrowed, every one left").isEqualTo(2);
        assertThat(notificationRepository.findAll()).filteredOn(n -> n.getUserId().equals(admin.getId()))
                .allMatch(Notification::isRead);
    }

    /** The inbox's "Mark all read" marks only what the narrowed inbox shows, as the bell's does (PC-4). */
    @Test
    void markingAllTheInboxReadTakesInOnlyTheRegionsAskedAbout() throws Exception {
        Map<String, Long> rows = threeInboxRows();
        JsonNode result = read(mockMvc.perform(southOnly(post("/api/inbox/mark-all-read"))));
        assertThat(result.get("updated").asInt()).isEqualTo(2);
        assertThat(emailRecipientRepository.findById(rows.get("NORTH")).orElseThrow().isRead()).isFalse();
        assertThat(emailRecipientRepository.findById(rows.get("SOUTH")).orElseThrow().isRead()).isTrue();
        assertThat(emailRecipientRepository.findById(rows.get("USER")).orElseThrow().isRead()).isTrue();
        assertThat(emailRecipientRepository.findById(rows.get("SOUTH")).orElseThrow().getReadAt()).isNotNull();

        Region west = region("WEST");
        Long noRegion = inboxRow(EmailEntityType.USER, sales.getId(), "About Sam again");
        JsonNode nowhere = read(mockMvc.perform(post("/api/inbox/mark-all-read").with(as(admin))
                .param("regionIds", west.getId().toString())));
        assertThat(nowhere.get("updated").asInt()).as("admin holds no WEST, so only the email about no region")
                .isEqualTo(1);
        assertThat(emailRecipientRepository.findById(rows.get("NORTH")).orElseThrow().isRead()).isFalse();
        assertThat(emailRecipientRepository.findById(noRegion).orElseThrow().isRead()).isTrue();

        JsonNode all = read(mockMvc.perform(post("/api/inbox/mark-all-read").with(as(admin))));
        assertThat(all.get("updated").asInt()).as("unnarrowed, every one left").isEqualTo(1);
        assertThat(emailRecipientRepository.findAll()).filteredOn(r -> r.getUserId().equals(admin.getId()))
                .allMatch(EmailRecipient::isRead);
    }

    /**
     * An email about a record since deleted stays in the region the record was last in (PC-5): its history's
     * newest region, or the default region when its history names none.
     */
    @Test
    void anEmailAboutADeletedRecordStaysInTheRegionItWasLastIn() throws Exception {
        Customer gone = customer("Gone Co", north);
        Long row = inboxRow(EmailEntityType.CUSTOMER, gone.getId(), "About Gone Co");
        mockMvc.perform(delete("/api/customers/" + gone.getId()).with(as(admin))).andExpect(status().isOk());
        assertThat(customerRepository.existsById(gone.getId())).isFalse();
        // A record whose history names no region at all is taken to have been in the default region.
        Long orphan = inboxRow(EmailEntityType.INVOICE, Long.MAX_VALUE - 7, "About an invoice long gone");
        Region fallback = defaultRegion();

        assertThat(inboxSubjects(north)).contains("About Gone Co").doesNotContain("About an invoice long gone");
        assertThat(inboxSubjects(south)).doesNotContain("About Gone Co", "About an invoice long gone");
        assertThat(inboxSubjects(fallback)).contains("About an invoice long gone").doesNotContain("About Gone Co");
        assertThat(inboxUnread(north)).as("Gone Co's email").isEqualTo(1);
        assertThat(inboxUnread(south)).isZero();
        assertThat(inboxUnread(fallback)).as("the orphan's email").isEqualTo(1);

        JsonNode southMarked = read(mockMvc.perform(southOnly(post("/api/inbox/mark-all-read"))));
        assertThat(southMarked.get("updated").asInt()).isZero();
        read(mockMvc.perform(post("/api/inbox/mark-all-read").with(as(admin))
                .param("regionIds", north.getId().toString())));
        assertThat(emailRecipientRepository.findById(row).orElseThrow().isRead()).isTrue();
        assertThat(emailRecipientRepository.findById(orphan).orElseThrow().isRead()).isFalse();
    }

    private List<String> inboxSubjects(Region region) throws Exception {
        JsonNode page = read(mockMvc.perform(get("/api/inbox").with(as(admin))
                .param("regionIds", region.getId().toString())));
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(n -> n.get("subject").asText()).toList();
    }

    private long inboxUnread(Region region) throws Exception {
        return read(mockMvc.perform(get("/api/inbox/unread-count").with(as(admin))
                .param("regionIds", region.getId().toString()))).get("count").asLong();
    }

    /**
     * Top paying keeps the database's order among equal amounts (CF-4): a single-region answer is the SQL
     * ORDER BY, never re-sorted by Java's string order. The name column is made case-insensitive for the test,
     * as a linguistic collation would order it, so the two orders differ.
     */
    @Test
    void topPayingKeepsTheDatabaseOrderAmongEqualAmounts() throws Exception {
        for (String name : List.of("alpha Co", "Bravo Co")) {
            Customer c = customer(name, south);
            Invoice inv = invoice(c, 1);
            paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(), new BigDecimal("50.00"),
                    "Cash", null, List.of(inv.getId()), collections.getId(), null));
        }
        jdbc.execute("ALTER TABLE customers ALTER COLUMN name SET DATA TYPE VARCHAR_IGNORECASE(150)");
        try {
            JsonNode south = getSouth("/api/dashboard/top-paying-customers");
            assertThat(south.get("customers")).extracting(n -> n.get("customerName").asText())
                    .as("ORDER BY collected desc, name asc, as the database compares names")
                    .containsExactly("South Co", "alpha Co", "Bravo Co");
        } finally {
            jdbc.execute("ALTER TABLE customers ALTER COLUMN name SET DATA TYPE VARCHAR(150)");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void askingForSouthAnswersSouthOnly(String endpoint) throws Exception {
        ROWS.get(endpoint).check(this);
    }

    // ---- requests -------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder southOnly(MockHttpServletRequestBuilder request) {
        return request.with(as(admin)).param("regionIds", south.getId().toString());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getSouth(String path) throws Exception {
        return read(mockMvc.perform(southOnly(get(path))));
    }

    private JsonNode getAll(String path) throws Exception {
        return read(mockMvc.perform(get(path).with(as(admin))));
    }

    private ResultActions postSouth(String path, Object body) throws Exception {
        return mockMvc.perform(southOnly(post(path)).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static Map<String, Object> allMatching(String action, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        body.put("selectAllMatchingFilter", true);
        body.put("params", params);
        return body;
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private List<Long> southOnlyIds() {
        return List.of(south.getId());
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    void list(String path, String nameField) throws Exception {
        JsonNode all = getAll(path);
        assertThat(all.get("content")).extracting(n -> n.get(nameField).asText())
                .contains("North Co", "South Co");

        JsonNode page = getSouth(path);
        assertThat(page.get("content")).isNotEmpty();
        assertThat(page.get("content")).extracting(n -> n.get(nameField).asText()).containsOnly("South Co");
        assertThat(page.get("content")).extracting(n -> n.get("regionId").asLong()).containsOnly(south.getId());
        assertThat(page.get("content")).extracting(n -> n.get("regionCode").asText()).containsOnly("SOUTH");
        assertThat(page.get("content")).extracting(n -> n.get("regionName").asText()).containsOnly("South");
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
        assertThat(page.get("bookRegionIds")).isEmpty();
        assertThat(page.get("lockedFilters")).isEmpty();
    }

    void summary(String path, String countField, long southCount) throws Exception {
        assertThat(getAll(path).get(countField).asLong()).isEqualTo(2 * southCount);
        assertThat(getSouth(path).get(countField).asLong()).isEqualTo(southCount);
    }

    void export(String path) throws Exception {
        String csv = postSouth(path, allMatching("EXPORT", Map.of())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("South Co").doesNotContain("North Co");
    }

    void customersBulk() throws Exception {
        JsonNode result = read(postSouth("/api/customers/bulk", allMatching("ADD_POC",
                Map.of("userId", collections.getId(), "pocType", PocType.COLLECTION.name()))));
        assertThat(ids(result.get("succeeded"))).containsExactly(south().customer().getId());
        assertThat(customerPocRepository.findByCustomerIdIn(List.of(north().customer().getId()))).isEmpty();
    }

    void invoicesBulk() throws Exception {
        JsonNode result = read(postSouth("/api/invoices/bulk", allMatching("CANCEL", Map.of())));
        assertThat(result.get("requested").asInt()).as("SOUTH's two invoices, and no more").isEqualTo(2);
        assertThat(ids(result.get("succeeded"))).containsExactly(south().unpaid().getId());
        assertThat(invoiceRepository.findById(north().unpaid().getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
        assertThat(invoiceRepository.findById(south().unpaid().getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    void paymentsBulk() throws Exception {
        User other = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        grant(other, south, AccessLevel.READ, RecordScope.BOOK);
        JsonNode result = read(postSouth("/api/payments/bulk",
                allMatching("REASSIGN_COLLECTION_POC", Map.of("userId", other.getId()))));
        assertThat(ids(result.get("succeeded"))).containsExactly(south().payment().getId());
        assertThat(paymentRepository.findById(north().payment().getId()).orElseThrow()
                .getCollectionPoc().getId()).isEqualTo(collections.getId());
    }

    void promisesBulk() throws Exception {
        JsonNode result = read(postSouth("/api/promises/bulk", allMatching("CANCEL", Map.of())));
        assertThat(ids(result.get("succeeded"))).containsExactly(south().promise().id());
        assertThat(promiseRepository.findById(north().promise().id()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.OPEN);
    }

    private BigDecimal thisMonth(JsonNode series) {
        String month = YearMonth.from(Today.date()).toString();
        for (JsonNode point : series.get("months")) {
            if (point.get("month").asText().equals(month)) return point.get("amount").decimalValue();
        }
        throw new AssertionError("no point for " + month);
    }

    private void scopedToSouth(JsonNode figure) {
        assertThat(ids(figure.at("/scope/regionIds"))).isEqualTo(southOnlyIds());
        assertThat(figure.at("/scope/bookRegionIds")).isEmpty();
        assertThat(figure.at("/scope/asOf").isNull()).isTrue();
        assertThat(figure.get("coverage").asText()).isEqualTo("ALL");
    }

    void billedByMonth() throws Exception {
        assertThat(thisMonth(getAll("/api/dashboard/billed-by-month"))).isEqualByComparingTo("800.00");
        JsonNode south = getSouth("/api/dashboard/billed-by-month");
        scopedToSouth(south);
        assertThat(thisMonth(south)).as("two SOUTH invoices of 300").isEqualByComparingTo("600.00");
    }

    void outstandingByAge() throws Exception {
        JsonNode south = getSouth("/api/dashboard/outstanding-by-age");
        scopedToSouth(south);
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode bucket : south.get("buckets")) total = total.add(bucket.get("amount").decimalValue());
        assertThat(total).as("600 billed less 70 paid").isEqualByComparingTo("530.00");
    }

    void topOutstanding() throws Exception {
        JsonNode south = getSouth("/api/dashboard/top-outstanding-customers");
        scopedToSouth(south);
        assertThat(south.get("customers")).extracting(n -> n.get("customerName").asText())
                .containsExactly("South Co");
    }

    void collectedByMonth() throws Exception {
        assertThat(thisMonth(getAll("/api/dashboard/collected-by-month"))).isEqualByComparingTo("110.00");
        JsonNode south = getSouth("/api/dashboard/collected-by-month");
        scopedToSouth(south);
        assertThat(thisMonth(south)).isEqualByComparingTo("70.00");
    }

    void topPaying() throws Exception {
        JsonNode south = getSouth("/api/dashboard/top-paying-customers");
        scopedToSouth(south);
        assertThat(south.get("customers")).extracting(n -> n.get("customerName").asText())
                .containsExactly("South Co");
        assertThat(south.at("/customers/0/collected").decimalValue()).isEqualByComparingTo("70.00");
    }

    void emailBulk() throws Exception {
        Map<String, Object> params = Map.of(
                "entityType", "INVOICE",
                "to", List.of(Map.of("type", "USER", "userId", admin.getId())),
                "subject", "Your invoice",
                "body", "Hello");
        JsonNode result = read(postSouth("/api/emails/bulk", allMatching("SEND_EMAIL", params)));
        assertThat(ids(result.get("succeeded")))
                .containsExactlyInAnyOrder(south().paid().getId(), south().unpaid().getId());
        assertThat(emailRepository.findAll()).extracting(Email::getEntityId)
                .containsExactlyInAnyOrder(south().paid().getId(), south().unpaid().getId());
    }

    /** Three emails to admin: about NORTH's invoice, SOUTH's invoice, and a user (which has no region). */
    private Map<String, Long> threeInboxRows() {
        Map<String, Long> rows = new HashMap<>();
        rows.put("NORTH", inboxRow(EmailEntityType.INVOICE, north().paid().getId(), "North invoice"));
        rows.put("SOUTH", inboxRow(EmailEntityType.INVOICE, south().paid().getId(), "South invoice"));
        rows.put("USER", inboxRow(EmailEntityType.USER, sales.getId(), "About Sam"));
        return rows;
    }

    private Long inboxRow(EmailEntityType type, Long entityId, String subject) {
        Email email = emailRepository.save(Email.builder()
                .entityType(type).entityId(entityId).entityLabel(subject)
                .direction(EmailDirection.OUTBOUND).status(EmailStatus.SENT)
                .subject(subject).body("Hello").fromName("Cara").fromInternal(true)
                .build());
        return emailRecipientRepository.save(EmailRecipient.builder()
                .email(email).field(RecipientField.TO).userId(admin.getId())
                .name("Admin").internal(true).sources("USER")
                .build()).getId();
    }

    void inbox() throws Exception {
        threeInboxRows();
        assertThat(getAll("/api/inbox").get("totalElements").asLong()).isEqualTo(3);
        JsonNode page = getSouth("/api/inbox");
        assertThat(page.get("content")).extracting(n -> n.get("subject").asText())
                .containsExactlyInAnyOrder("South invoice", "About Sam");
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
    }

    void inboxUnreadCount() throws Exception {
        threeInboxRows();
        assertThat(getAll("/api/inbox/unread-count").get("count").asLong()).isEqualTo(3);
        assertThat(getSouth("/api/inbox/unread-count").get("count").asLong())
                .as("SOUTH's email and the one about no region").isEqualTo(2);
    }

    void inboxBulk() throws Exception {
        Map<String, Long> rows = threeInboxRows();
        JsonNode result = read(postSouth("/api/inbox/bulk", allMatching("MARK_READ", Map.of())));
        assertThat(ids(result.get("succeeded"))).containsExactlyInAnyOrder(rows.get("SOUTH"), rows.get("USER"));
        assertThat(emailRecipientRepository.findById(rows.get("NORTH")).orElseThrow().isRead()).isFalse();
    }

    /** A sales POC who works only in NORTH: offered for NORTH, never for SOUTH. */
    void assignablePocs() throws Exception {
        User nora = user("nora.north", DataSeeder.ROLE_SALES_POC);
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.READ, RecordScope.BOOK);
        assertThat(getAll("/api/pocs/assignable?type=SALES")).extracting(n -> n.get("username").asText())
                .contains("nora.north", "sam.sales");
        JsonNode south = read(mockMvc.perform(southOnly(get("/api/pocs/assignable")).param("type", "SALES")));
        assertThat(south).extracting(n -> n.get("username").asText())
                .contains("sam.sales").doesNotContain("nora.north");
    }

    void myScope() throws Exception {
        assertThat(getAll("/api/pocs/my-scope").get("regions")).extracting(n -> n.get("regionId").asLong())
                .contains(north.getId(), south.getId());
        JsonNode south = getSouth("/api/pocs/my-scope");
        assertThat(south.get("regions")).singleElement().satisfies(r -> {
            assertThat(r.get("regionId").asLong()).isEqualTo(this.south.getId());
            assertThat(r.get("code").asText()).isEqualTo("SOUTH");
            assertThat(r.get("clearable").asBoolean()).isTrue();
            assertThat(r.get("sales").asBoolean()).isTrue();
        });
        assertThat(south.get("clearable").asBoolean()).isTrue();
    }

    void tasksBulk() throws Exception {
        JsonNode result = read(postSouth("/api/tasks/bulk", allMatching("MARK_DONE", Map.of())));
        assertThat(ids(result.get("succeeded"))).containsExactly(south().taskId());
        assertThat(taskRepository.findById(north().taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(taskRepository.findById(south().taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    /** The assignee picker offers staff who hold the regions asked about, and nobody else (SC-18). */
    void assigneePeople() throws Exception {
        User nora = user("nora.north", DataSeeder.ROLE_SALES_POC);
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.READ, RecordScope.BOOK);
        User stan = user("stan.south", DataSeeder.ROLE_SALES_POC);
        grantRepository.deleteAllOfUser(stan.getId());
        grant(stan, south, AccessLevel.READ, RecordScope.BOOK);

        assertThat(getAll("/api/assignees/people")).extracting(n -> n.get("username").asText())
                .contains("nora.north", "stan.south", "sam.sales");
        assertThat(getSouth("/api/assignees/people")).extracting(n -> n.get("username").asText())
                .contains("stan.south", "sam.sales").doesNotContain("nora.north");
        JsonNode onNorth = read(mockMvc.perform(get("/api/assignees/people").with(as(admin))
                .param("entityType", "CUSTOMER").param("entityId", north().customer().getId().toString())
                .param("regionIds", south.getId().toString())));
        assertThat(onNorth).as("a record's own region, whatever is asked").extracting(n -> n.get("username").asText())
                .contains("nora.north").doesNotContain("stan.south");
    }

    /** Three notices to admin: about NORTH's invoice, SOUTH's invoice, and nothing in any region. */
    private Map<String, Long> threeNotifications() {
        notificationRepository.deleteAll();
        Map<String, Long> rows = new HashMap<>();
        rows.put("NORTH", notification("North invoice", north.getId()));
        rows.put("SOUTH", notification("South invoice", south.getId()));
        rows.put("NONE", notification("Reconnect Gmail", null));
        return rows;
    }

    private Long notification(String title, Long regionId) {
        return notificationRepository.save(Notification.builder().userId(admin.getId()).type("TEST")
                .title(title).message(title).regionId(regionId).build()).getId();
    }

    void notifications() throws Exception {
        assertThat(notificationRepository.findAll()).as("each fixture dispute told admin, tagged with its region")
                .filteredOn(n -> n.getUserId().equals(admin.getId()))
                .extracting(Notification::getRegionId).containsExactlyInAnyOrder(north.getId(), south.getId());
        threeNotifications();
        assertThat(getAll("/api/notifications").get("totalElements").asLong()).isEqualTo(3);
        JsonNode page = getSouth("/api/notifications");
        assertThat(page.get("content")).extracting(n -> n.get("title").asText())
                .containsExactlyInAnyOrder("South invoice", "Reconnect Gmail");
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
    }

    void notificationsUnreadCount() throws Exception {
        threeNotifications();
        assertThat(getAll("/api/notifications/unread-count").get("count").asLong()).isEqualTo(3);
        assertThat(getSouth("/api/notifications/unread-count").get("count").asLong())
                .as("SOUTH's notice and the one about no region").isEqualTo(2);
    }

    void notificationsBulk() throws Exception {
        Map<String, Long> rows = threeNotifications();
        JsonNode result = read(postSouth("/api/notifications/bulk", allMatching("MARK_READ", Map.of())));
        assertThat(ids(result.get("succeeded"))).containsExactlyInAnyOrder(rows.get("SOUTH"), rows.get("NONE"));
        assertThat(notificationRepository.findById(rows.get("NORTH")).orElseThrow().isRead()).isFalse();
    }

    /**
     * Each region's unpaid invoice cancelled by a cashier who works in both, and held there (every cancel is above
     * a limit of 0): a change waiting in NORTH and one in SOUTH, both for admin to decide.
     */
    private void cancelsHeldInBothRegions() {
        User maker = user("mia.maker", "CASHIER");
        grantRepository.deleteAllOfUser(maker.getId());
        for (Region r : List.of(north, south)) {
            grant(maker, r, AccessLevel.MANAGE, RecordScope.ALL);
            approvalThresholdRepository.save(ApprovalThreshold.builder().region(r)
                    .operation(ApprovalOperation.INVOICE_CANCEL).amount(BigDecimal.ZERO).windowHours(0)
                    .updatedByUserId(admin.getId()).updatedAt(AppClock.now()).build());
        }
        actAs(maker);
        for (Fixture f : List.of(north(), south())) {
            assertThat(invoiceService.cancelOrHold(f.unpaid().getId()).heldChange()).isPresent();
        }
        actAs(admin);
    }

    void approvals() throws Exception {
        cancelsHeldInBothRegions();
        assertThat(getAll("/api/approvals").get("content")).extracting(n -> n.get("customerName").asText())
                .containsExactlyInAnyOrder("North Co", "South Co");
        JsonNode page = getSouth("/api/approvals");
        assertThat(page.get("content")).extracting(n -> n.get("customerName").asText()).containsExactly("South Co");
        assertThat(page.get("content")).extracting(n -> n.get("regionId").asLong()).containsOnly(south.getId());
        assertThat(page.get("content")).extracting(n -> n.get("regionName").asText()).containsOnly("South");
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
        assertThat(page.get("bookRegionIds")).isEmpty();
        assertThat(page.get("lockedFilters")).isEmpty();
    }

    void approvalsSummary() throws Exception {
        cancelsHeldInBothRegions();
        JsonNode all = getAll("/api/approvals/summary");
        assertThat(all.get("pendingCount").asLong()).isEqualTo(2);
        assertThat(all.get("awaitingMeCount").asLong()).isEqualTo(2);
        JsonNode south = getSouth("/api/approvals/summary");
        assertThat(south.get("pendingCount").asLong()).isEqualTo(1);
        assertThat(south.get("pendingAmount").decimalValue()).as("SOUTH's invoice of 3 × 100")
                .isEqualByComparingTo("300.00");
    }

    void approvalsAwaitingMe() throws Exception {
        cancelsHeldInBothRegions();
        assertThat(getAll("/api/approvals/awaiting-me/count").get("count").asLong()).isEqualTo(2);
        assertThat(getSouth("/api/approvals/awaiting-me/count").get("count").asLong()).isEqualTo(1);
    }

    void approvalsExport() throws Exception {
        cancelsHeldInBothRegions();
        export("/api/approvals/export");
    }

    // ---- rules (§5.10.10): a rule acts where it is restricted to, or where its owner works -------------------

    private static Map<String, Object> ruleBody(String name, List<Long> regionIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("enabled", true);
        body.put("trigger", Map.of("kind", "ENTITY_UPDATED", "entityType", "INVOICE"));
        body.put("regionIds", regionIds);
        body.put("condition", Map.of("op", "AND", "children", List.of(Map.of("filter", "customerName:contains:Co"))));
        body.put("actions", List.of(Map.of("type", "CREATE_TASK", "title", "Chase {{Invoice.Number}}")));
        return body;
    }

    private Long saveRule(String name, List<Long> regionIds) throws Exception {
        return read(mockMvc.perform(post("/api/automation/rules").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(ruleBody(name, regionIds))))).get("id").asLong();
    }

    private void oneRuleInEachRegion() throws Exception {
        saveRule("North rule", List.of(north.getId()));
        saveRule("South rule", List.of(south.getId()));
    }

    void rules() throws Exception {
        oneRuleInEachRegion();
        assertThat(getAll("/api/automation/rules").get("content")).extracting(n -> n.get("name").asText())
                .containsExactlyInAnyOrder("North rule", "South rule");
        JsonNode page = getSouth("/api/automation/rules");
        assertThat(page.get("content")).extracting(n -> n.get("name").asText()).containsExactly("South rule");
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
        assertThat(page.get("lockedFilters")).isEmpty();
    }

    void rulesSummary() throws Exception {
        oneRuleInEachRegion();
        assertThat(getAll("/api/automation/rules/summary").get("active").asLong()).isEqualTo(2);
        assertThat(getSouth("/api/automation/rules/summary").get("active").asLong()).isEqualTo(1);
    }

    /** What a rule matches is counted over the regions asked about. */
    void rulePreview() throws Exception {
        Map<String, Object> preview = Map.of("rule", ruleBody("Everywhere", List.of()), "sampleSize", 10);
        JsonNode all = read(mockMvc.perform(post("/api/automation/rules/preview").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(preview))));
        assertThat(all.get("matchCount").asLong()).isEqualTo(4);
        JsonNode south = read(postSouth("/api/automation/rules/preview", preview));
        assertThat(south.get("matchCount").asLong()).isEqualTo(2);
        assertThat(south.get("sample")).extracting(n -> n.get("id").asLong())
                .containsExactlyInAnyOrder(south().paid().getId(), south().unpaid().getId());
    }

    void savedRulePreview() throws Exception {
        Long id = saveRule("Everywhere", List.of());
        assertThat(getAll("/api/automation/rules/" + id + "/preview").get("matchCount").asLong()).isEqualTo(4);
        JsonNode south = getSouth("/api/automation/rules/" + id + "/preview");
        assertThat(south.get("matchCount").asLong()).isEqualTo(2);
        assertThat(south.get("sample")).extracting(n -> n.get("label").asText())
                .allMatch(label -> label.startsWith("Invoice "));
    }

    void ruleMatches() throws Exception {
        Long id = saveRule("Everywhere", List.of());
        assertThat(getAll("/api/automation/rules/" + id + "/matches").get("totalElements").asLong()).isEqualTo(4);
        JsonNode page = getSouth("/api/automation/rules/" + id + "/matches");
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content")).extracting(n -> n.get("id").asLong())
                .containsExactlyInAnyOrder(south().paid().getId(), south().unpaid().getId());
        assertThat(ids(page.get("regionIds"))).isEqualTo(southOnlyIds());
    }
}
