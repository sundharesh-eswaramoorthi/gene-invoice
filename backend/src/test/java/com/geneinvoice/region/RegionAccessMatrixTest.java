package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.document.DocumentDtos;
import com.geneinvoice.document.DocumentService;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes are authorised against the record's own region (B1, §5.6.6). Rita is a cashier who may also run
 * promises and decide disputes; she reads NORTH (READ/ALL), manages SOUTH (MANAGE/ALL) and holds nothing in
 * WEST. In NORTH she sees everything and changes nothing (403), in SOUTH she changes what her role allows
 * (200), and WEST does not exist for her (404). After every refusal the database is exactly as it was.
 */
class RegionAccessMatrixTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired RecordAccess recordAccess;
    @Autowired PocService pocService;
    @Autowired DocumentService documentService;

    User admin;
    User rita;
    User sales;
    User collections;
    Region north;
    Region south;
    Region west;
    Product widget;
    Map<String, Fixture> fixtures = new HashMap<>();

    /** Per region: a customer, an invoice to edit and cancel, another under dispute, a payment and a promise. */
    record Fixture(Customer customer, Invoice invoice, Invoice disputed, Payment payment,
                   PromiseDtos.PromiseDto promise, Long disputeId) {}

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        west = region("WEST");
        for (Region r : List.of(north, south, west)) grant(admin, r, AccessLevel.APPROVE, RecordScope.ALL);
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        // A POC is assignable only where they hold a grant (§5.6.7): these two work in every region.
        for (Region r : List.of(north, south, west)) {
            grant(sales, r, AccessLevel.READ, RecordScope.BOOK);
            grant(collections, r, AccessLevel.READ, RecordScope.BOOK);
        }
        widget = product("Widget", "100.00");
        fixtures.put("NORTH", fixture("North Co", north));
        fixtures.put("SOUTH", fixture("South Co", south));
        fixtures.put("WEST", fixture("West Co", west));

        Role cashier = role("CASHIER");
        Role ritas = roleRepository.findByName("RITA_CASHIER")
                .orElseGet(() -> Role.builder().name("RITA_CASHIER").description("RITA_CASHIER").build());
        ritas.setPrivileges(new HashSet<>(cashier.getPrivileges()));
        Stream.of(Privileges.PROMISE_MANAGE, Privileges.PROMISE_OVERRIDE, Privileges.DISPUTE_VIEW,
                        Privileges.DISPUTE_MANAGE)
                .forEach(p -> ritas.getPrivileges().add(privilegeRepository.findByName(p).orElseThrow()));
        roleRepository.save(ritas);
        rita = user("rita", "RITA_CASHIER");
        grantRepository.deleteAllOfUser(rita.getId());
        grant(rita, north, AccessLevel.READ, RecordScope.ALL);
        grant(rita, south, AccessLevel.MANAGE, RecordScope.ALL);
        actAs(admin);
    }

    private Fixture fixture(String name, Region region) {
        actAs(admin);
        Customer c = customer(name, region);
        Invoice invoice = invoice(c);
        Invoice disputed = invoice(c);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal("30.00"), "Cash", null, List.of(disputed.getId()), collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(c.getId(),
                new BigDecimal("25.00"), Today.date().plusDays(3), collections.getId(), null, List.of()));
        actAs(customerUser(name.toLowerCase().replace(' ', '.') + ".login", c.getId()));
        Long dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                disputed.getId(), "Wrong notes", "{\"action\":\"update_notes\",\"notes\":\"Agreed\"}")).getId();
        actAs(admin);
        return new Fixture(c, invoice, disputed, payment, promise, dispute);
    }

    private Invoice invoice(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private Fixture in(Region r) {
        return fixtures.get(r.getCode());
    }

    /** Everything a refused write could have touched, read straight from the tables. */
    private String state() {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("customers", "users", "invoices", "payments", "payment_allocations",
                "payment_promises", "disputes", "customer_pocs", "invoice_items")) {
            all.append(table).append(jdbc.queryForList("select * from " + table + " order by id")).append('\n');
        }
        all.append("audit_logs ").append(jdbc.queryForObject("select count(*) from audit_logs", Long.class));
        all.append(" notifications ").append(jdbc.queryForObject("select count(*) from notifications", Long.class));
        return all.toString();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(as(rita)));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** A refused write: the status it is refused with, and nothing written. */
    private void refused(ResultMatcher expected, MockHttpServletRequestBuilder request, Object body) throws Exception {
        String before = state();
        var response = (body == null ? call(request) : send(request, body)).andExpect(expected).andReturn();
        assertThat(state()).as("nothing changed by the refused " + response.getRequest().getMethod() + " "
                + response.getRequest().getRequestURI()).isEqualTo(before);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void listsAnswerTheGrantedRegionsAndByIdReadsReachEveryGrantedOne() throws Exception {
        JsonNode all = read(call(get("/api/invoices")).andExpect(status().isOk()));
        assertThat(all.get("content")).extracting(n -> n.get("customerName").asText())
                .containsOnly("North Co", "South Co");
        assertThat(ids(all.get("regionIds"))).containsExactly(north.getId(), south.getId());

        JsonNode northOnly = read(call(get("/api/invoices").param("regionIds", north.getId().toString()))
                .andExpect(status().isOk()));
        assertThat(northOnly.get("content")).extracting(n -> n.get("customerName").asText()).containsOnly("North Co");

        JsonNode westOnly = read(call(get("/api/invoices").param("regionIds", west.getId().toString()))
                .andExpect(status().isOk()));
        assertThat(westOnly.get("totalElements").asLong()).isZero();
        assertThat(westOnly.get("regionIds")).isEmpty();

        call(get("/api/invoices/" + in(north).invoice().getId())).andExpect(status().isOk());
        call(get("/api/invoices/" + in(south).invoice().getId())).andExpect(status().isOk());
        call(get("/api/invoices/" + in(west).invoice().getId())).andExpect(status().isNotFound());
    }

    @Test
    void anInvoiceIsEditedAndCancelledOnlyWhereItsRegionIsManaged() throws Exception {
        Map<String, Object> edit = body("notes", "Changed by rita");
        refused(status().isForbidden(), patch("/api/invoices/" + in(north).invoice().getId()), edit);
        refused(status().isNotFound(), patch("/api/invoices/" + in(west).invoice().getId()), edit);
        refused(status().isForbidden(), post("/api/invoices/" + in(north).invoice().getId() + "/cancel"), null);
        refused(status().isNotFound(), post("/api/invoices/" + in(west).invoice().getId() + "/cancel"), null);

        JsonNode edited = read(send(patch("/api/invoices/" + in(south).invoice().getId()), edit)
                .andExpect(status().isOk()));
        assertThat(edited.get("notes").asText()).isEqualTo("Changed by rita");
        JsonNode cancelled = read(call(post("/api/invoices/" + in(south).invoice().getId() + "/cancel"))
                .andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aPaymentAPromiseAndACustomerAreChangedOnlyWhereTheirRegionIsManaged() throws Exception {
        Map<String, Object> paymentEdit = body("notes", "Changed by rita");
        refused(status().isForbidden(), patch("/api/payments/" + in(north).payment().getId()), paymentEdit);
        refused(status().isNotFound(), patch("/api/payments/" + in(west).payment().getId()), paymentEdit);
        send(patch("/api/payments/" + in(south).payment().getId()), paymentEdit).andExpect(status().isOk());

        Map<String, Object> promiseEdit = body("amount", "30.00", "promisedDate", Today.date().plusDays(5).toString(),
                "notes", "Changed by rita");
        refused(status().isForbidden(), put("/api/promises/" + in(north).promise().id()), promiseEdit);
        refused(status().isNotFound(), put("/api/promises/" + in(west).promise().id()), promiseEdit);
        send(put("/api/promises/" + in(south).promise().id()), promiseEdit).andExpect(status().isOk());

        Map<String, Object> override = body("status", "BROKEN", "reason", "Called and they refused");
        refused(status().isForbidden(), post("/api/promises/" + in(north).promise().id() + "/override"), override);
        refused(status().isNotFound(), post("/api/promises/" + in(west).promise().id() + "/override"), override);
        refused(status().isForbidden(), delete("/api/promises/" + in(north).promise().id() + "/override"), null);
        send(post("/api/promises/" + in(south).promise().id() + "/override"), override).andExpect(status().isOk());

        Map<String, Object> cancel = body("reason", "Raised in error");
        refused(status().isForbidden(), post("/api/promises/" + in(north).promise().id() + "/cancel"), cancel);
        refused(status().isNotFound(), post("/api/promises/" + in(west).promise().id() + "/cancel"), cancel);
        send(post("/api/promises/" + in(south).promise().id() + "/cancel"), cancel).andExpect(status().isOk());

        Map<String, Object> rename = body("name", "Renamed by rita");
        refused(status().isForbidden(), put("/api/customers/" + in(north).customer().getId()), rename);
        refused(status().isNotFound(), put("/api/customers/" + in(west).customer().getId()), rename);
        refused(status().isForbidden(), delete("/api/customers/" + in(north).customer().getId()), null);
        refused(status().isNotFound(), delete("/api/customers/" + in(west).customer().getId()), null);
        JsonNode renamed = read(send(put("/api/customers/" + in(south).customer().getId()), rename)
                .andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("Renamed by rita");
    }

    @Test
    void recordsAreCreatedOnlyForCustomersInAManagedRegionAndTakeTheirRegion() throws Exception {
        for (Region r : List.of(north, west)) {
            var expected = r == north ? status().isForbidden() : status().isNotFound();
            Long customerId = in(r).customer().getId();
            refused(expected, post("/api/invoices"), body("customerId", customerId, "salesPocUserId", sales.getId(),
                    "items", List.of(body("productId", widget.getId(), "quantity", 1))));
            refused(expected, post("/api/payments"), body("customerId", customerId, "amount", "10.00",
                    "collectionPocUserId", collections.getId()));
            refused(expected, post("/api/promises"), body("customerId", customerId, "amount", "10.00",
                    "promisedDate", Today.date().plusDays(2).toString(), "collectionPocUserId", collections.getId()));
        }

        Long southCo = in(south).customer().getId();
        JsonNode invoice = read(send(post("/api/invoices"), body("customerId", southCo, "salesPocUserId",
                sales.getId(), "items", List.of(body("productId", widget.getId(), "quantity", 1))))
                .andExpect(status().isOk()));
        JsonNode payment = read(send(post("/api/payments"), body("customerId", southCo, "amount", "10.00",
                "collectionPocUserId", collections.getId())).andExpect(status().isOk()));
        JsonNode promise = read(send(post("/api/promises"), body("customerId", southCo, "amount", "10.00",
                "promisedDate", Today.date().plusDays(2).toString(), "collectionPocUserId", collections.getId()))
                .andExpect(status().isOk()));
        for (JsonNode created : List.of(invoice, payment, promise)) {
            assertThat(created.get("regionId").asLong()).isEqualTo(south.getId());
        }
        assertThat(invoiceRepository.findById(invoice.get("id").asLong()).orElseThrow().getRegion().getId())
                .isEqualTo(south.getId());
    }

    @Test
    void aCustomerIsCreatedOnlyInARegionTheCallerManagesAndThatIsActive() throws Exception {
        refused(status().isForbidden(), post("/api/customers"), body("name", "Northern Ltd",
                "username", "northern.login", "password", "secret1", "regionId", north.getId()));

        Region east = region("EAST");
        grant(rita, east, AccessLevel.MANAGE, RecordScope.ALL);
        String before = state();
        JsonNode unsure = read(send(post("/api/customers"), body("name", "Somewhere Ltd",
                "username", "somewhere.login", "password", "secret1")).andExpect(status().isBadRequest()));
        assertThat(unsure.at("/fieldErrors/regionId").asText()).isEqualTo("Choose the region this customer belongs to");
        assertThat(state()).isEqualTo(before);

        Region dormant = region("DORMANT");
        dormant.setActive(false);
        regionRepository.save(dormant);
        grant(rita, dormant, AccessLevel.MANAGE, RecordScope.ALL);
        refused(status().isBadRequest(), post("/api/customers"), body("name", "Dormant Ltd",
                "username", "dormant.login", "password", "secret1", "regionId", dormant.getId()));

        JsonNode created = read(send(post("/api/customers"), body("name", "Southern Ltd",
                "username", "southern.login", "password", "secret1", "regionId", south.getId()))
                .andExpect(status().isOk()));
        assertThat(created.get("regionId").asLong()).isEqualTo(south.getId());
    }

    @Test
    void aDisputeIsDecidedOnlyWhereItsRegionIsManaged() throws Exception {
        Map<String, Object> decision = body("adminNotes", "Looked at it");
        refused(status().isForbidden(), post("/api/disputes/" + in(north).disputeId() + "/approve"), decision);
        refused(status().isNotFound(), post("/api/disputes/" + in(west).disputeId() + "/approve"), decision);
        refused(status().isForbidden(), post("/api/disputes/" + in(north).disputeId() + "/deny"), decision);
        refused(status().isNotFound(), post("/api/disputes/" + in(west).disputeId() + "/deny"), decision);

        JsonNode approved = read(send(post("/api/disputes/" + in(south).disputeId() + "/approve"), decision)
                .andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(invoiceRepository.findById(in(south).disputed().getId()).orElseThrow().getNotes())
                .isEqualTo("Agreed");
    }

    @Test
    void recomputeLooksOnlyAtTheRegionsWhereTheCallerMayOverridePromises() throws Exception {
        for (Fixture f : fixtures.values()) {
            PaymentPromise p = promiseRepository.findById(f.promise().id()).orElseThrow();
            p.setFulfilledAmount(new BigDecimal("1.00"));
            promiseRepository.save(p);
        }

        JsonNode mine = read(call(post("/api/promises/recompute")).andExpect(status().isOk()));
        assertThat(mine).extracting(n -> n.get("customerName").asText()).containsExactly("South Co");

        JsonNode narrowed = read(call(post("/api/promises/recompute").param("regionIds", north.getId().toString()))
                .andExpect(status().isOk()));
        assertThat(narrowed).isEmpty();

        JsonNode everyone = read(mockMvc.perform(post("/api/promises/recompute").with(as(admin)))
                .andExpect(status().isOk()));
        assertThat(everyone).extracting(n -> n.get("customerName").asText())
                .as("the dry run wrote nothing, and NORTH and WEST were only left out for rita")
                .containsExactlyInAnyOrder("North Co", "South Co", "West Co");
    }

    @Test
    void aBulkActionLeavesRowsTheCallerMayOnlyReadAsNotReachable() throws Exception {
        List<Long> requested = List.of(in(north).invoice().getId(), in(south).invoice().getId(),
                in(west).invoice().getId());
        JsonNode result = read(send(post("/api/invoices/bulk"), body("action", "CANCEL", "ids", requested))
                .andExpect(status().isOk()));
        assertThat(ids(result.get("succeeded"))).containsExactly(in(south).invoice().getId());
        Map<Long, String> skipped = StreamSupport.stream(result.get("skipped").spliterator(), false)
                .collect(Collectors.toMap(n -> n.get("id").asLong(), n -> n.get("reason").asText()));
        assertThat(skipped).containsOnly(
                Map.entry(in(north).invoice().getId(), BulkExecutor.NOT_REACHABLE),
                Map.entry(in(west).invoice().getId(), BulkExecutor.NOT_REACHABLE));
        assertThat(invoiceRepository.findById(in(north).invoice().getId()).orElseThrow().getStatus().name())
                .isEqualTo("UNPAID");

        User colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        grant(colin, south, AccessLevel.READ, RecordScope.BOOK);
        JsonNode reassigned = read(send(post("/api/promises/bulk"), body("action", "REASSIGN_COLLECTION_POC",
                "selectAllMatchingFilter", true, "params", body("userId", colin.getId())))
                .andExpect(status().isOk()));
        assertThat(ids(reassigned.get("succeeded"))).as("select-all takes in only the rows rita may change")
                .containsExactly(in(south).promise().id());
        assertThat(promiseRepository.findById(in(south).promise().id()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(colin.getId());
        assertThat(promiseRepository.findById(in(north).promise().id()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(collections.getId());

        JsonNode payments = read(send(post("/api/payments/bulk"), body("action", "REASSIGN_COLLECTION_POC",
                "selectAllMatchingFilter", true, "params", body("userId", colin.getId())))
                .andExpect(status().isOk()));
        assertThat(ids(payments.get("succeeded"))).containsExactly(in(south).payment().getId());
        assertThat(paymentRepository.findById(in(south).payment().getId()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(colin.getId());
        assertThat(paymentRepository.findById(in(north).payment().getId()).orElseThrow().getCollectionPoc().getId())
                .isEqualTo(collections.getId());

        List<Long> customers = List.of(in(north).customer().getId(), in(south).customer().getId(),
                in(west).customer().getId());
        JsonNode seats = read(send(post("/api/customers/bulk"), body("action", "ADD_POC", "ids", customers,
                "params", body("userId", colin.getId(), "pocType", "COLLECTION")))
                .andExpect(status().isOk()));
        assertThat(ids(seats.get("succeeded"))).containsExactly(in(south).customer().getId());
        assertThat(StreamSupport.stream(seats.get("skipped").spliterator(), false)
                .map(n -> n.get("reason").asText())).containsOnly(BulkExecutor.NOT_REACHABLE);
        assertThat(customerPocRepository.findAll()).filteredOn(seat -> seat.getUser().getId().equals(colin.getId()))
                .extracting(seat -> seat.getCustomer().getId()).containsExactly(in(south).customer().getId());
    }

    /**
     * Recompute reaches only what an override by hand would: a book-only collector sees and rewrites only the
     * customers whose live promises are all theirs, and a customer login that may override only its own.
     */
    @Test
    void recomputeReachesOnlyThePromisesTheCallerCouldOverrideByHand() throws Exception {
        User colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        grantRepository.deleteAllOfUser(colin.getId());
        grant(colin, south, AccessLevel.MANAGE, RecordScope.BOOK);
        actAs(admin);
        Customer mine = customer("Colin Co", south);
        Customer shared = customer("Shared Co", south);
        Long colinsOwn = promise(mine, colin);
        Long colinsShared = promise(shared, colin);
        Long carasShared = promise(shared, collections);
        List<Long> all = List.of(colinsOwn, colinsShared, carasShared, in(south).promise().id(),
                in(north).promise().id());
        for (Long id : all) {
            PaymentPromise p = promiseRepository.findById(id).orElseThrow();
            p.setFulfilledAmount(new BigDecimal("1.00"));
            promiseRepository.save(p);
        }

        JsonNode preview = read(mockMvc.perform(post("/api/promises/recompute").with(as(colin)))
                .andExpect(status().isOk()));
        assertThat(preview).extracting(n -> n.get("promiseId").asLong()).containsExactly(colinsOwn);
        mockMvc.perform(post("/api/promises/recompute").param("apply", "true").with(as(colin)))
                .andExpect(status().isOk());
        assertThat(fulfilled(colinsOwn)).isEqualByComparingTo("0.00");
        for (Long untouched : List.of(colinsShared, carasShared, in(south).promise().id(), in(north).promise().id())) {
            assertThat(fulfilled(untouched)).as("promise " + untouched).isEqualByComparingTo("1.00");
        }

        Role overrider = roleRepository.findByName("CUSTOMER_OVERRIDER").orElseGet(() -> Role.builder()
                .name("CUSTOMER_OVERRIDER").description("Customer login that may override promises").build());
        overrider.setPrivileges(new HashSet<>(role("CUSTOMER").getPrivileges()));
        overrider.getPrivileges().add(privilegeRepository.findByName(Privileges.PROMISE_OVERRIDE).orElseThrow());
        roleRepository.save(overrider);
        User login = customerUser("shared.login", shared.getId());
        login.setRole(overrider);
        userRepository.save(login);
        JsonNode own = read(mockMvc.perform(post("/api/promises/recompute").with(as(login))
                .param("regionIds", north.getId().toString())).andExpect(status().isOk()));
        assertThat(own).extracting(n -> n.get("customerName").asText()).containsOnly("Shared Co").hasSize(2);
    }

    private Long promise(Customer c, User poc) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(c.getId(), new BigDecimal("40.00"),
                Today.date().plusDays(4), poc.getId(), null, List.of())).id();
    }

    private BigDecimal fulfilled(Long promiseId) {
        return promiseRepository.findById(promiseId).orElseThrow().getFulfilledAmount();
    }

    /**
     * Every multi-row write takes its customer's lock before its own row (§5.11.5; CF-8, SC-7, SC-19). With the
     * customer held by another transaction, the write must wait, and while it waits it must hold no lock on its
     * own row: a third session locks that row at once. A write that locked the row first, or never waited for
     * the customer, fails here.
     */
    @Test
    void everyWriteLocksTheCustomerBeforeItsOwnRow() throws Exception {
        Fixture s = in(south);
        Long customer = s.customer().getId();
        Long promise = s.promise().id();

        takesTheCustomerFirst("invoice edit", customer, "invoices", s.invoice().getId(),
                () -> send(patch("/api/invoices/" + s.invoice().getId()), body("notes", "Locked")));
        takesTheCustomerFirst("invoice cancel", customer, "invoices", s.invoice().getId(),
                () -> call(post("/api/invoices/" + s.invoice().getId() + "/cancel")));
        takesTheCustomerFirst("promise edit", customer, "payment_promises", promise,
                () -> send(put("/api/promises/" + promise), body("amount", "30.00",
                        "promisedDate", Today.date().plusDays(5).toString())));
        takesTheCustomerFirst("promise override", customer, "payment_promises", promise,
                () -> send(post("/api/promises/" + promise + "/override"),
                        body("status", "BROKEN", "reason", "Called and they refused")));
        takesTheCustomerFirst("clearing an override", customer, "payment_promises", promise,
                () -> call(delete("/api/promises/" + promise + "/override")));
        takesTheCustomerFirst("dispute approval", customer, "disputes", s.disputeId(),
                () -> send(post("/api/disputes/" + s.disputeId() + "/approve"), body("adminNotes", "Fine")));
        takesTheCustomerFirst("recompute", customer, "payment_promises", promise,
                () -> call(post("/api/promises/recompute").param("apply", "true")));

        Customer lateCo = customer("Late Co", south);
        PaymentPromise late = promiseRepository.findById(promise(lateCo, collections)).orElseThrow();
        late.setPromisedDate(Today.date().minusDays(1));
        promiseRepository.save(late);
        takesTheCustomerFirst("promise sweep", lateCo.getId(), "payment_promises", late.getId(),
                promiseService::sweepOverdue);
        assertThat(promiseRepository.findById(late.getId()).orElseThrow().getStatus().name())
                .as("the sweep did evaluate the overdue promise").isNotEqualTo("OPEN");

        Fixture n = in(north);
        takesTheCustomerFirst("dispute denial", n.customer().getId(), "disputes", n.disputeId(),
                () -> mockMvc.perform(post("/api/disputes/" + n.disputeId() + "/deny").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body("adminNotes", "No")))));
    }

    private void takesTheCustomerFirst(String write, Long customerId, String table, Long rowId,
                                       Callable<?> body) throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try (Connection holder = session()) {
            lock(holder, "customers", customerId);
            Future<?> running = writer.submit(body);
            awaitWaitingOnALock(write, running);
            try (Connection probe = session()) {
                try (Statement st = probe.createStatement()) {
                    st.execute("set lock_timeout 200");
                }
                try {
                    lock(probe, table, rowId);
                } catch (SQLException e) {
                    fail(write + " locked its " + table + " row before its customer: " + e.getMessage());
                }
                probe.rollback();
            }
            holder.rollback();
            Object outcome = running.get(10, TimeUnit.SECONDS);
            if (outcome instanceof ResultActions result) {
                assertThat(result.andReturn().getResponse().getStatus()).as(write).isEqualTo(200);
            }
        } finally {
            writer.shutdownNow();
        }
    }

    private Connection session() throws SQLException {
        Connection c = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"), "");
        c.setAutoCommit(false);
        return c;
    }

    private static void lock(Connection c, String table, Long id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("select id from " + table + " where id = ? for update")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as(table + " " + id).isTrue();
            }
        }
    }

    /** Until some session waits on a lock; the write finishing first means it never waited for the customer. */
    private void awaitWaitingOnALock(String write, Future<?> running) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) {
                running.get();
                fail(write + " finished without waiting for its customer's lock");
            }
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail(write + " was never seen waiting for its customer's lock");
    }

    // ---- BE-04b: flags, documents, email and seats ------------------------------------------------------------

    private JsonNode getAs(User caller, String path) throws Exception {
        return read(mockMvc.perform(get(path).with(as(caller))).andExpect(status().isOk()));
    }

    /**
     * Every record says what rita may do to it in its own region (D14): nothing in NORTH, what her role allows in
     * SOUTH. Lists and details agree, audit snapshots carry no flags, and a historical view allows nothing.
     */
    @Test
    void recordsSayWhatTheCallerMayDoToThemInTheirRegion() throws Exception {
        for (Region r : List.of(north, south)) {
            boolean mine = r == south;
            Fixture f = in(r);
            JsonNode invoice = getAs(rita, "/api/invoices/" + f.invoice().getId());
            assertThat(invoice.get("canManage").asBoolean()).as("invoice in " + r.getCode()).isEqualTo(mine);
            JsonNode payment = getAs(rita, "/api/payments/" + f.payment().getId());
            assertThat(payment.get("canManage").asBoolean()).as("payment in " + r.getCode()).isEqualTo(mine);
            JsonNode promise = getAs(rita, "/api/promises/" + f.promise().id());
            assertThat(promise.get("canManage").asBoolean()).as("promise in " + r.getCode()).isEqualTo(mine);
            assertThat(promise.get("canOverride").asBoolean()).as("override in " + r.getCode()).isEqualTo(mine);
            JsonNode customer = getAs(rita, "/api/customers/" + f.customer().getId());
            assertThat(customer.get("canManage").asBoolean()).as("customer in " + r.getCode()).isEqualTo(mine);
            assertThat(customer.get("canMoveRegion").asBoolean())
                    .as("SOUTH is the only region rita manages customers in, so there is nowhere to move to")
                    .isFalse();
            JsonNode dispute = getAs(rita, "/api/disputes/" + f.disputeId());
            assertThat(dispute.get("canResolve").asBoolean()).as("dispute in " + r.getCode()).isEqualTo(mine);
        }

        JsonNode list = getAs(rita, "/api/invoices");
        for (JsonNode row : list.get("content")) {
            assertThat(row.get("canManage").asBoolean()).as(row.get("customerName").asText())
                    .isEqualTo(row.get("regionId").asLong() == south.getId());
        }
        for (String path : List.of("/api/payments", "/api/promises", "/api/customers")) {
            for (JsonNode row : getAs(rita, path).get("content")) {
                assertThat(row.get("canManage").asBoolean()).as(path)
                        .isEqualTo(row.get("regionId").asLong() == south.getId());
            }
        }
        for (JsonNode row : getAs(rita, "/api/disputes").get("content")) {
            assertThat(row.get("canResolve").asBoolean()).isEqualTo(row.get("regionId").asLong() == south.getId());
        }

        Region east = region("EAST");
        grant(rita, east, AccessLevel.MANAGE, RecordScope.ALL);
        assertThat(getAs(rita, "/api/customers/" + in(south).customer().getId()).get("canMoveRegion").asBoolean())
                .as("EAST is somewhere else rita manages customers").isTrue();
        assertThat(getAs(rita, "/api/customers/" + in(north).customer().getId()).get("canMoveRegion").asBoolean())
                .isFalse();
        east.setActive(false);
        regionRepository.save(east);
        assertThat(getAs(rita, "/api/customers/" + in(south).customer().getId()).get("canMoveRegion").asBoolean())
                .as("an inactive region is not somewhere to move to").isFalse();

        send(post("/api/disputes/" + in(south).disputeId() + "/approve"), body("adminNotes", "Fine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResolve").value(false));
        send(patch("/api/invoices/" + in(south).invoice().getId()), body("notes", "Flag check"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("select coalesce(before_json, '') || coalesce(after_json, '') from audit_logs",
                String.class)).as("an audit snapshot never carries the caller's rights")
                .isNotEmpty()
                .noneMatch(json -> json.contains("canManage") || json.contains("canResolve"));

        actAs(rita);
        assertThat(recordAccess.canManage(ScopeResolver.Kind.INVOICE, south.getId())).isTrue();
        ViewScopeHolder.set(new ViewScope(null, AsOfPoint.endOfDay(Today.date().minusDays(1))));
        assertThat(recordAccess.canManage(ScopeResolver.Kind.INVOICE, south.getId()))
                .as("nothing can be changed in a historical view").isFalse();
        assertThat(recordAccess.can(Privileges.PROMISE_OVERRIDE, south.getId())).isFalse();
        ViewScopeHolder.clear();
    }

    private MockHttpServletRequestBuilder upload(Long invoiceId) {
        return multipart("/api/documents")
                .file(new MockMultipartFile("file", "terms.pdf", "application/pdf",
                        "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n"
                                .getBytes(StandardCharsets.UTF_8)))
                .param("entityType", "INVOICE")
                .param("entityId", String.valueOf(invoiceId));
    }

    private long documents() {
        return jdbc.queryForObject("select count(*) from documents where deleted = false", Long.class);
    }

    /** Documents are changed only where both the record and its documents may be managed, in its region. */
    @Test
    void documentsAreChangedOnlyWhereTheRecordsRegionIsManaged() throws Exception {
        Long northDoc = read(mockMvc.perform(upload(in(north).invoice().getId()).with(as(admin)))
                .andExpect(status().isCreated())).get("id").asLong();
        // rita's own upload from before she lost NORTH: still not hers to change there.
        jdbc.update("update documents set uploaded_by_user_id = ? where id = ?", rita.getId(), northDoc);

        long before = documents();
        call(upload(in(north).invoice().getId())).andExpect(status().isForbidden());
        call(upload(in(west).invoice().getId())).andExpect(status().isNotFound());
        assertThat(documents()).isEqualTo(before);

        JsonNode southDoc = read(call(upload(in(south).invoice().getId())).andExpect(status().isCreated()));
        assertThat(southDoc.get("canEdit").asBoolean()).isTrue();
        assertThat(southDoc.get("canDelete").asBoolean()).isTrue();

        JsonNode northList = read(call(get("/api/documents").param("entityType", "INVOICE")
                .param("entityId", in(north).invoice().getId().toString())).andExpect(status().isOk()));
        assertThat(northList.at("/content/0/canDownload").asBoolean()).isTrue();
        assertThat(northList.at("/content/0/canEdit").asBoolean()).isFalse();
        assertThat(northList.at("/content/0/canDelete").asBoolean())
                .as("the uploader may take back a file only where they may manage documents").isFalse();

        refused(status().isForbidden(), patch("/api/documents/" + northDoc), body("description", "Mine"));
        refused(status().isForbidden(), delete("/api/documents/" + northDoc), null);
        assertThat(documents()).isEqualTo(before + 1);

        // A day later, looking back at the day it was uploaded: the file may be opened, never changed (D14). The
        // documents endpoint refuses a past date until its as-of slice, so the service is asked directly.
        MovableClock.SHARED.advance(Duration.ofDays(1));
        actAs(rita);
        ViewScopeHolder.set(new ViewScope(null, AsOfPoint.endOfDay(Today.date().minusDays(1))));
        try {
            DocumentDtos.DocumentDto then = documentService.list("INVOICE", in(south).invoice().getId(), null, null)
                    .content().get(0);
            assertThat(then.id()).isEqualTo(southDoc.get("id").asLong());
            assertThat(then.canDownload()).isTrue();
            assertThat(then.canEdit()).as("nothing is changed in a historical view").isFalse();
            assertThat(then.canDelete()).as("not even by its uploader").isFalse();
        } finally {
            ViewScopeHolder.clear();
        }

        call(delete("/api/documents/" + southDoc.get("id").asLong())).andExpect(status().isNoContent());
        assertThat(documents()).isEqualTo(before);
    }

    private long emails() {
        return jdbc.queryForObject("select count(*) from emails", Long.class);
    }

    /**
     * Email is sent only about records in a region where the caller may send, and a person named as a recipient
     * or as the sender must be able to reach the record's region (PC-2).
     */
    @Test
    void emailGoesOnlyFromWhereTheCallerMaySendAndOnlyToPeopleWhoCanReachTheRecord() throws Exception {
        User nora = user("nora.north", DataSeeder.ROLE_SALES_POC);
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.READ, RecordScope.ALL);
        Map<String, Object> toNora = Map.of("type", "USER", "userId", nora.getId());
        Map<String, Object> toSam = Map.of("type", "USER", "userId", sales.getId());

        refused(status().isForbidden(), post("/api/emails"), mail(in(north).invoice(), List.of(toSam)));
        refused(status().isNotFound(), post("/api/emails"), mail(in(west).invoice(), List.of(toSam)));
        assertThat(emails()).isZero();

        JsonNode to = read(send(post("/api/emails"), mail(in(south).invoice(), List.of(toSam, toNora)))
                .andExpect(status().isBadRequest()));
        assertThat(to.at("/fieldErrors/to[1]").asText()).isEqualTo("NORA.NORTH has no access to South");
        assertThat(to.at("/fieldErrors/to[0]").isMissingNode()).isTrue();
        Map<String, Object> fromNora = new LinkedHashMap<>(mail(in(south).invoice(), List.of(toSam)));
        fromNora.put("from", toNora);
        JsonNode from = read(send(post("/api/emails"), fromNora).andExpect(status().isBadRequest()));
        assertThat(from.at("/fieldErrors/from").asText()).isEqualTo("NORA.NORTH has no access to South");
        send(post("/api/emails/preview"), fromNora).andExpect(status().isBadRequest());
        assertThat(emails()).as("nothing saved by a refused send").isZero();

        JsonNode bulk = read(send(post("/api/emails/bulk"), body("action", "SEND_EMAIL",
                "ids", List.of(in(south).invoice().getId()), "params", body("entityType", "INVOICE",
                        "to", List.of(toNora), "subject", "Hello", "body", "Hi"))).andExpect(status().isOk()));
        assertThat(bulk.get("succeeded")).isEmpty();
        assertThat(bulk.at("/skipped/0/reason").asText()).isEqualTo("NORA.NORTH has no access to South");

        send(post("/api/emails"), mail(in(south).invoice(), List.of(toSam))).andExpect(status().isOk());
        assertThat(emails()).isEqualTo(1);
    }

    /** Every email row and copy, straight from the tables: a refused retry requeues nothing. */
    private String emailState() {
        return jdbc.queryForList("select * from emails order by id").toString()
                + jdbc.queryForList("select * from email_recipients order by id");
    }

    /** A failed email is retried only where the caller may send about its record (§5.6.6), as a new one is. */
    @Test
    void aFailedEmailIsRetriedOnlyWhereTheCallerMaySend() throws Exception {
        Map<String, Long> failed = new HashMap<>();
        for (Region r : List.of(north, south, west)) {
            JsonNode sent = read(mockMvc.perform(post("/api/emails").with(as(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(mail(in(r).invoice(), List.of(Map.of("type", "USER", "userId", sales.getId()))))))
                    .andExpect(status().isOk()));
            assertThat(sent.get("canRetry").asBoolean()).as(r.getCode()).isTrue();
            failed.put(r.getCode(), sent.get("id").asLong());
        }
        mailTransport.reset();

        for (Region r : List.of(north, west)) {
            String before = emailState();
            call(post("/api/emails/" + failed.get(r.getCode()) + "/retry"))
                    .andExpect(r == north ? status().isForbidden() : status().isNotFound());
            assertThat(emailState()).as("nothing requeued in " + r.getCode()).isEqualTo(before);
        }
        assertThat(mailTransport.submissions()).isEmpty();

        String before = emailState();
        call(post("/api/emails/" + failed.get("SOUTH") + "/retry")).andExpect(status().isOk());
        assertThat(emailState()).as("SOUTH's email was requeued and tried again").isNotEqualTo(before);
    }

    private static Map<String, Object> mail(Invoice about, List<?> to) {
        return body("entityType", "INVOICE", "entityId", about.getId(), "to", to,
                "subject", "About your invoice", "body", "Hello");
    }

    /** POC seats are changed only where the caller may assign POCs, and only to people who work in that region. */
    @Test
    void seatsAreChangedOnlyWhereTheCallerMayAssignPocsInTheCustomersRegion() throws Exception {
        actAs(admin);
        Long northSeat = pocService.add(in(north).customer().getId(), PocType.COLLECTION,
                collections.getId(), true).getId();
        Map<String, Object> seat = body("pocType", "COLLECTION", "userId", collections.getId());

        refused(status().isForbidden(), post("/api/customers/" + in(north).customer().getId() + "/pocs"), seat);
        refused(status().isNotFound(), post("/api/customers/" + in(west).customer().getId() + "/pocs"), seat);
        refused(status().isForbidden(),
                delete("/api/customers/" + in(north).customer().getId() + "/pocs/" + northSeat), null);
        refused(status().isForbidden(),
                post("/api/customers/" + in(north).customer().getId() + "/pocs/" + northSeat + "/primary"), null);

        User nora = user("nora.north", DataSeeder.ROLE_COLLECTION_POC);
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.READ, RecordScope.ALL);
        String before = state();
        JsonNode notHere = read(send(post("/api/customers/" + in(south).customer().getId() + "/pocs"),
                body("pocType", "COLLECTION", "userId", nora.getId())).andExpect(status().isBadRequest()));
        assertThat(notHere.get("message").asText())
                .isEqualTo("User nora.north is not assignable as Collection POC in region SOUTH");
        assertThat(state()).isEqualTo(before);

        send(post("/api/customers/" + in(south).customer().getId() + "/pocs"), seat).andExpect(status().isOk());
        assertThat(customerPocRepository.findByCustomerIdIn(List.of(in(south).customer().getId())))
                .extracting(s -> s.getUser().getId()).containsExactly(collections.getId());
    }

    /** A deleted customer's history stays readable, but only to those who can reach the region it was in. */
    @Test
    void aDeletedCustomersHistoryIsReadOnlyByThoseWhoCanReachItsRegion() throws Exception {
        Role auditor = roleRepository.findByName("REGION_AUDITOR")
                .orElseGet(() -> Role.builder().name("REGION_AUDITOR").description("REGION_AUDITOR").build());
        auditor.setPrivileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.AUDIT_VIEW)
                .map(p -> privilegeRepository.findByName(p).orElseThrow()).collect(Collectors.toCollection(HashSet::new)));
        roleRepository.save(auditor);
        User northAuditor = user("nell.auditor", "REGION_AUDITOR");
        grantRepository.deleteAllOfUser(northAuditor.getId());
        grant(northAuditor, north, AccessLevel.READ, RecordScope.ALL);
        User southAuditor = user("seb.auditor", "REGION_AUDITOR");
        grantRepository.deleteAllOfUser(southAuditor.getId());
        grant(southAuditor, south, AccessLevel.READ, RecordScope.ALL);

        Customer gone = customer("Gone Co", north);
        mockMvc.perform(delete("/api/customers/" + gone.getId()).with(as(admin))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select region_id from audit_logs where action = 'CUSTOMER_DELETED'"
                + " and entity_id = ?", Long.class, gone.getId())).isEqualTo(north.getId());

        JsonNode history = read(mockMvc.perform(get("/api/audit").param("entityType", "CUSTOMER")
                .param("entityId", gone.getId().toString()).with(as(northAuditor))).andExpect(status().isOk()));
        assertThat(history).extracting(n -> n.get("action").asText()).contains("CUSTOMER_DELETED");
        mockMvc.perform(get("/api/audit").param("entityType", "CUSTOMER").param("entityId", gone.getId().toString())
                .with(as(southAuditor))).andExpect(status().isNotFound());
    }

    @Test
    void aWriteAskedAsOfAPastDateIsRefused() throws Exception {
        JsonNode refusal = read(send(patch("/api/invoices/" + in(south).invoice().getId())
                .param("asOf", Today.date().minusDays(1).toString()), body("notes", "Back in time"))
                .andExpect(status().isBadRequest()));
        assertThat(refusal.get("error").asText()).isEqualTo("AS_OF_READ_ONLY");
        assertThat(invoiceRepository.findById(in(south).invoice().getId()).orElseThrow().getNotes()).isNull();
    }
}
