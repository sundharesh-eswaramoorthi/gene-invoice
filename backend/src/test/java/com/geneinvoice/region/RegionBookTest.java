package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The POC book, region by region (B1): where a POC holds SCOPE_OVERRIDE at ALL they see the whole region,
 * where they hold BOOK they see their own records, and the figures add up the same way. With one region and
 * today's roles, every answer is exactly what it was before regions (F2). A POC who sees only their book cannot
 * create anything for a customer outside it either (SC-12).
 */
class RegionBookTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PocService pocService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired NotificationService notificationService;

    User admin;
    User sid;
    User collections;
    Region north;
    Region south;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        // A POC is assignable only where they hold a grant (§5.6.7): these two work in every region.
        for (Region r : List.of(north, south)) {
            grant(sid, r, AccessLevel.READ, RecordScope.BOOK);
            grant(collections, r, AccessLevel.READ, RecordScope.BOOK);
        }
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    /** Roles outlive a test (the reset keeps them), so one made here is found again and given exactly these. */
    private Role newRole(String name, String... privileges) {
        Role r = roleRepository.findByName(name)
                .orElseGet(() -> Role.builder().name(name).description(name).build());
        r.setPrivileges(Stream.of(privileges)
                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                .collect(Collectors.toCollection(HashSet::new)));
        return roleRepository.save(r);
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

    /** A sales POC who may see a whole region wherever their grant is ALL: in full in NORTH, their book in SOUTH. */
    private User lena() {
        newRole("BOOK_SALES_LEAD", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGNABLE_SALES,
                Privileges.SCOPE_OVERRIDE);
        return staff("lena.lead", "BOOK_SALES_LEAD", north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.BOOK);
    }

    private Invoice invoice(Customer customer, User salesPoc, int quantity) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, null))));
    }

    private void pay(Customer customer, String amount, User collectionPoc, Invoice... invoices) {
        actAs(admin);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(), new BigDecimal(amount),
                "Cash", null, Stream.of(invoices).map(Invoice::getId).toList(), collectionPoc.getId(), null));
    }

    private JsonNode getOk(User caller, String path, String... params) throws Exception {
        var request = get(path).with(as(caller));
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static BigDecimal thisMonth(JsonNode series) {
        String month = YearMonth.from(Today.date()).toString();
        for (JsonNode point : series.get("months")) {
            if (point.get("month").asText().equals(month)) return point.get("amount").decimalValue();
        }
        throw new AssertionError("no point for " + month);
    }

    @Test
    void aFullRegionAndABookRegionShowTheWholeOfOneAndTheCallersOwnInTheOther() throws Exception {
        User lena = lena();
        Customer northCo = customer("North Co", north);
        Customer southCo = customer("South Co", south);
        Invoice northOther = invoice(northCo, sid, 1);
        Invoice southMine = invoice(southCo, lena, 2);
        Invoice southOther = invoice(southCo, sid, 3);

        JsonNode page = getOk(lena, "/api/invoices");
        assertThat(page.get("content")).extracting(n -> n.get("id").asLong())
                .containsExactlyInAnyOrder(northOther.getId(), southMine.getId());
        assertThat(page.get("lockedFilters")).as("a mixed view carries no chip").isEmpty();
        assertThat(ids(page.get("regionIds"))).containsExactly(north.getId(), south.getId());
        assertThat(ids(page.get("bookRegionIds"))).containsExactly(south.getId());
        assertThat(page.get("content")).extracting(n -> n.get("regionCode").asText())
                .containsExactlyInAnyOrder("NORTH", "SOUTH");

        JsonNode tiles = getOk(lena, "/api/invoices/summary");
        assertThat(tiles.get("count").asLong()).isEqualTo(2);
        assertThat(tiles.get("totalBilled").decimalValue()).as("100 in NORTH, and lena's own 200 in SOUTH")
                .isEqualByComparingTo("300.00");
        mockMvc.perform(get("/api/invoices/" + southOther.getId()).with(as(lena))).andExpect(status().isNotFound());
    }

    @Test
    void aPureBookUserKeepsTheChipAndNarrowingToNothingShowsNone() throws Exception {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        grant(sam, south, AccessLevel.MANAGE, RecordScope.BOOK);
        Customer acme = customer("Acme Ltd");
        Invoice mine = invoice(acme, sam, 1);
        invoice(acme, sid, 1);
        Region west = region("WEST");

        JsonNode page = getOk(sam, "/api/invoices");
        assertThat(page.get("content")).extracting(n -> n.get("id").asLong())
                .containsExactly(mine.getId());
        assertThat(page.get("lockedFilters")).extracting(JsonNode::asText)
                .containsExactly("salesPocUserId:eq:" + sam.getId());
        assertThat(ids(page.get("bookRegionIds"))).containsExactly(defaultRegion().getId(), south.getId());

        JsonNode dashboard = getOk(sam, "/api/dashboard/billed-by-month");
        assertThat(dashboard.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(ids(dashboard.at("/scope/bookRegionIds"))).containsExactly(defaultRegion().getId(), south.getId());

        for (String asked : List.of(west.getId().toString(), north.getId().toString())) {
            JsonNode nothing = getOk(sam, "/api/invoices", "regionIds", asked);
            assertThat(nothing.get("totalElements").asLong()).isZero();
            assertThat(nothing.get("lockedFilters")).as("empty because of the regions asked, not the book").isEmpty();
            assertThat(nothing.get("regionIds")).isEmpty();
        }
    }

    /**
     * CF-18 (§5.6.4 step 3): narrowing to a region the caller holds but sees no invoices in (no SCOPE_OVERRIDE at ALL
     * there, and not a sales POC) shows nothing, and no chip, since they see invoices in another region.
     */
    @Test
    void narrowingToAGrantedRegionWhoseTierIsNoneShowsNoneAndNoChip() throws Exception {
        newRole("INVOICE_WATCHER", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE);
        User sam = staff("sam.watcher", "INVOICE_WATCHER", north, AccessLevel.READ, RecordScope.BOOK,
                south, AccessLevel.READ, RecordScope.ALL);
        Invoice northInvoice = invoice(customer("North Co", north), sid, 1);
        Invoice southInvoice = invoice(customer("South Co", south), sid, 1);

        JsonNode all = getOk(sam, "/api/invoices");
        assertThat(all.get("content")).extracting(n -> n.get("id").asLong())
                .containsExactly(southInvoice.getId());
        assertThat(all.get("lockedFilters")).isEmpty();
        assertThat(ids(all.get("regionIds"))).containsExactly(south.getId());

        JsonNode nothing = getOk(sam, "/api/invoices", "regionIds", north.getId().toString());
        assertThat(nothing.get("totalElements").asLong()).isZero();
        assertThat(nothing.get("lockedFilters")).as("another granted region is visible: no chip").isEmpty();
        assertThat(nothing.get("regionIds")).isEmpty();
        mockMvc.perform(get("/api/invoices/" + northInvoice.getId()).with(as(sam))).andExpect(status().isNotFound());
    }

    @Test
    void theDashboardAddsWholePaymentsWhereTheCallerSeesEverythingAndTheirOwnAllocationsWhereTheyHaveABook()
            throws Exception {
        User lena = lena();
        Customer northCo = customer("North Co", north);
        Customer southCo = customer("South Co", south);
        Invoice northInvoice = invoice(northCo, sid, 5);
        Invoice southMine = invoice(southCo, lena, 2);
        Invoice southOther = invoice(southCo, sid, 3);
        pay(northCo, "100.00", collections, northInvoice);
        pay(southCo, "120.00", collections, southMine);
        pay(southCo, "80.00", collections, southOther);

        JsonNode collected = getOk(lena, "/api/dashboard/collected-by-month");
        assertThat(collected.get("coverage").asText()).isEqualTo("MIXED");
        assertThat(thisMonth(collected))
                .as("NORTH's whole payment of 100, and the 120 allocated to lena's own SOUTH invoice")
                .isEqualByComparingTo("220.00");
        assertThat(ids(collected.at("/scope/regionIds"))).containsExactly(north.getId(), south.getId());
        assertThat(ids(collected.at("/scope/bookRegionIds"))).containsExactly(south.getId());

        JsonNode paying = getOk(lena, "/api/dashboard/top-paying-customers");
        assertThat(paying.get("coverage").asText()).isEqualTo("MIXED");
        assertThat(paying.get("customers")).extracting(n -> n.get("customerName").asText())
                .containsExactly("South Co", "North Co");
        assertThat(paying.at("/customers/0/collected").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(paying.at("/customers/1/collected").decimalValue()).isEqualByComparingTo("100.00");

        JsonNode billed = getOk(lena, "/api/dashboard/billed-by-month");
        assertThat(billed.get("coverage").asText()).isEqualTo("MIXED");
        assertThat(thisMonth(billed)).as("NORTH's 500 and lena's own 200").isEqualByComparingTo("700.00");

        JsonNode southOnly = getOk(lena, "/api/dashboard/collected-by-month", "regionIds", south.getId().toString());
        assertThat(southOnly.get("coverage").asText()).isEqualTo("BOOK");
        assertThat(thisMonth(southOnly)).isEqualByComparingTo("120.00");
        JsonNode northOnly = getOk(lena, "/api/dashboard/collected-by-month", "regionIds", north.getId().toString());
        assertThat(northOnly.get("coverage").asText()).isEqualTo("ALL");
        assertThat(thisMonth(northOnly)).isEqualByComparingTo("100.00");
    }

    /**
     * Top paying customers from a full region and a book region are merged by amount, and a tie across the two reads
     * by name ignoring case, not region by region (RR-3).
     */
    @Test
    void aTieInTopPayingAcrossAFullAndABookRegionIsOrderedByName() throws Exception {
        User lena = lena();
        Customer zeta = customer("Zeta", north);
        Customer alpha = customer("alpha", south);
        Invoice zetaInvoice = invoice(zeta, sid, 1);
        Invoice alphaMine = invoice(alpha, lena, 1);
        pay(zeta, "100.00", collections, zetaInvoice);
        pay(alpha, "100.00", collections, alphaMine);

        JsonNode paying = getOk(lena, "/api/dashboard/top-paying-customers");
        assertThat(paying.get("coverage").asText()).isEqualTo("MIXED");
        assertThat(paying.get("customers")).extracting(n -> n.get("customerName").asText())
                .containsExactly("alpha", "Zeta");
        assertThat(paying.at("/customers/0/collected").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(paying.at("/customers/1/collected").decimalValue()).isEqualByComparingTo("100.00");
    }

    /**
     * One region, every role shape: the figures are those of the formula before regions. Two invoices (100 by
     * sam, 200 by sid); 60 paid on sam's by cara, 90 on sid's by cody.
     */
    @Test
    void withOneRegionTheFiguresAreTheSameAsBeforeRegionsForEveryKindOfRole() throws Exception {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        newRole("BOOK_COLLECTOR", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.POC_VIEW, Privileges.POC_ASSIGNABLE_COLLECTION);
        User cara = user("cara.collector", "BOOK_COLLECTOR");
        User cody = user("cody.collector", "BOOK_COLLECTOR");
        newRole("BOOK_PAYMENT_WATCHER", Privileges.PAYMENT_VIEW, Privileges.SCOPE_OVERRIDE);
        User watcher = user("wendy.watcher", "BOOK_PAYMENT_WATCHER");
        Customer acme = customer("Acme Ltd");
        Invoice samInvoice = invoice(acme, sam, 1);
        Invoice sidInvoice = invoice(acme, sid, 2);
        pay(acme, "60.00", cara, samInvoice);
        pay(acme, "90.00", cody, sidInvoice);
        Long home = defaultRegion().getId();

        record Expected(User caller, String collected, String coverage, List<Long> book) {}
        for (Expected e : List.of(
                new Expected(admin, "150.00", "ALL", List.of()),
                new Expected(watcher, "150.00", "ALL", List.of()),
                new Expected(sam, "60.00", "BOOK", List.of(home)),
                new Expected(cara, "60.00", "BOOK", List.of(home)),
                new Expected(cody, "90.00", "BOOK", List.of(home)))) {
            String who = e.caller().getUsername();
            JsonNode collected = getOk(e.caller(), "/api/dashboard/collected-by-month");
            assertThat(thisMonth(collected)).as(who).isEqualByComparingTo(e.collected());
            assertThat(collected.get("coverage").asText()).as(who).isEqualTo(e.coverage());
            assertThat(ids(collected.at("/scope/bookRegionIds"))).as(who).isEqualTo(e.book());

            JsonNode paying = getOk(e.caller(), "/api/dashboard/top-paying-customers");
            assertThat(paying.at("/customers/0/collected").decimalValue()).as(who)
                    .isEqualByComparingTo(e.collected());
            assertThat(paying.at("/customers/0/payments").asLong()).as(who)
                    .isEqualTo(e.collected().equals("150.00") ? 2 : 1);
            assertThat(paying.get("coverage").asText()).as(who).isEqualTo(e.coverage());
        }
    }

    private long rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    /**
     * A sales POC whose role may raise invoices, payments and promises but who has no SCOPE_OVERRIDE sees only
     * their book, and a customer outside it is missing for every create: 404, and no row of any kind written.
     * A customer in their book, through an invoice they own, takes all three.
     */
    @Test
    void aPureBookPocCannotCreateAnythingForACustomerOutsideTheirBook() throws Exception {
        newRole("BOOK_SALES_WRITER", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE, Privileges.PROMISE_VIEW,
                Privileges.PROMISE_MANAGE, Privileges.POC_VIEW, Privileges.POC_ASSIGNABLE_SALES);
        User bea = user("bea.book", "BOOK_SALES_WRITER");
        Customer mine = customer("Mine Ltd");
        invoice(mine, bea, 1);
        Customer other = customer("Other Ltd");
        invoice(other, sid, 1);

        List<String> tables = List.of("invoices", "invoice_items", "payments", "payment_allocations",
                "payment_promises", "audit_logs");
        Map<String, Long> before = new java.util.HashMap<>();
        tables.forEach(t -> before.put(t, rows(t)));
        for (var create : creates(other, bea)) {
            mockMvc.perform(post(create.getKey()).with(as(bea)).contentType(MediaType.APPLICATION_JSON)
                    .content(json(create.getValue()))).andExpect(status().isNotFound());
        }
        tables.forEach(t -> assertThat(rows(t)).as(t + " after the refused creates").isEqualTo(before.get(t)));

        for (var create : creates(mine, bea)) {
            mockMvc.perform(post(create.getKey()).with(as(bea)).contentType(MediaType.APPLICATION_JSON)
                    .content(json(create.getValue()))).andExpect(status().isOk());
        }
    }

    // ---- BE-04b: the POC book per region ----------------------------------------------------------------------

    /**
     * A person can be a POC only where they hold a grant (§5.6.7): naming someone who works only in the default
     * region on a NORTH invoice is refused with the region's name, and nothing is written.
     */
    @Test
    void someoneWithNoGrantInTheRegionCannotBeMadeItsPoc() throws Exception {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        Customer northCo = customer("North Co", north);
        long invoicesBefore = rows("invoices");

        String message = objectMapper.readTree(mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("customerId", northCo.getId(),
                                "salesPocUserId", sam.getId(),
                                "items", List.of(Map.of("productId", widget.getId(), "quantity", 1))))))
                .andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("message").asText();
        assertThat(message).isEqualTo("User sam.sales is not assignable as Sales POC in region NORTH");
        assertThat(rows("invoices")).isEqualTo(invoicesBefore);

        Invoice sids = invoice(northCo, sid, 1);
        mockMvc.perform(patch("/api/invoices/" + sids.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("salesPocUserId", sam.getId()))))
                .andExpect(status().isBadRequest());
        assertThat(invoiceRepository.findById(sids.getId()).orElseThrow().getSalesPoc().getId())
                .isEqualTo(sid.getId());

        grant(sam, north, AccessLevel.READ, RecordScope.BOOK);
        mockMvc.perform(patch("/api/invoices/" + sids.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("salesPocUserId", sam.getId()))))
                .andExpect(status().isOk());
        assertThat(notificationRepository.findAll()).filteredOn(n -> n.getUserId().equals(sam.getId()))
                .singleElement().satisfies(n -> assertThat(n.getRegionId()).isEqualTo(north.getId()));
    }

    private List<String> assignable(User caller, String... params) throws Exception {
        List<String> all = new java.util.ArrayList<>(List.of("type", "SALES"));
        all.addAll(List.of(params));
        JsonNode people = getOk(caller, "/api/pocs/assignable", all.toArray(String[]::new));
        return StreamSupport.stream(people.spliterator(), false).map(n -> n.get("username").asText()).toList();
    }

    /** The picker offers only people who work in the region the record is, or will be, in. */
    @Test
    void thePickerOffersOnlyPeopleWhoWorkInTheRecordsRegion() throws Exception {
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User lena = lena();
        Customer northCo = customer("North Co", north);
        Customer southCo = customer("South Co", south);

        assertThat(assignable(admin, "customerId", northCo.getId().toString()))
                .containsExactlyInAnyOrder("admin", "lena.lead", "sid.sales");
        assertThat(assignable(admin, "customerId", southCo.getId().toString()))
                .containsExactlyInAnyOrder("admin", "lena.lead", "sid.sales");
        assertThat(assignable(admin, "regionId", defaultRegion().getId().toString()))
                .contains("sam.sales").doesNotContain("lena.lead");
        assertThat(assignable(admin)).as("every region admin holds")
                .contains("sam.sales", "lena.lead", "sid.sales");
        assertThat(assignable(admin, "regionIds", north.getId().toString())).doesNotContain("sam.sales");

        assertThat(assignable(lena, "regionId", defaultRegion().getId().toString()))
                .as("a region lena holds nothing in answers nobody").isEmpty();
        mockMvc.perform(get("/api/pocs/assignable").with(as(sam)).param("type", "SALES")
                .param("customerId", northCo.getId().toString())).andExpect(status().isNotFound());

        JsonNode check = getOk(sam, "/api/invoices/assignable-check");
        assertThat(check.get("sales").asBoolean()).isTrue();
        Customer home = customer("Home Co");
        invoice(home, sam, 1);
        assertThat(getOk(sam, "/api/invoices/assignable-check", "customerId", home.getId().toString())
                .get("sales").asBoolean()).as("a customer in sam's book, in a region he works in").isTrue();
        mockMvc.perform(get("/api/invoices/assignable-check").with(as(sam))
                .param("customerId", northCo.getId().toString())).andExpect(status().isNotFound());
        newRole("NORTH_READER", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.POC_VIEW,
                Privileges.SCOPE_OVERRIDE);
        User reader = staff("rhea.reader", "NORTH_READER", north, AccessLevel.READ, RecordScope.ALL);
        assertThat(getOk(reader, "/api/invoices/assignable-check", "customerId", northCo.getId().toString())
                .get("sales").asBoolean()).isFalse();
    }

    /**
     * A seat holder who has lost the customer's region holds no role on it (§5.6.7): the default Collection POC
     * and an email to the role both pass over them, though the seat itself is kept.
     */
    @Test
    void aSeatHolderWithoutTheRegionIsNeitherTheDefaultNorEmailedAsTheRole() throws Exception {
        User colin = user("colin.collections", DataSeeder.ROLE_COLLECTION_POC);
        Customer southCo = customer("South Co", south);
        customerPocRepository.save(CustomerPoc.builder().customer(southCo).user(colin)
                .pocType(PocType.COLLECTION).primary(true).createdByUserId(admin.getId()).build());
        customerPocRepository.save(CustomerPoc.builder().customer(southCo).user(collections)
                .pocType(PocType.COLLECTION).primary(false).createdByUserId(admin.getId()).build());

        actAs(admin);
        assertThat(pocService.defaultAssignee(southCo.getId(), PocType.COLLECTION)).get()
                .extracting(User::getUsername).isEqualTo("cara.collections");
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(southCo.getId(),
                new BigDecimal("10.00"), Today.date().plusDays(2), null, null, List.of()));
        assertThat(promise.collectionPoc().username()).isEqualTo("cara.collections");

        JsonNode sent = objectMapper.readTree(mockMvc.perform(post("/api/emails").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "entityType", "CUSTOMER", "entityId", southCo.getId(),
                                "to", List.of(Map.of("type", "ROLE", "role", "COLLECTION_POC")),
                                "subject", "Your account", "body", "Hello"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(sent.get("to")).extracting(n -> n.get("userId").asLong()).containsExactly(collections.getId());
        assertThat(customerPocRepository.findByCustomerIdIn(List.of(southCo.getId()))).hasSize(2);

        grant(colin, south, AccessLevel.READ, RecordScope.BOOK);
        assertThat(pocService.defaultAssignee(southCo.getId(), PocType.COLLECTION)).get()
                .extracting(User::getUsername).isEqualTo("colin.collections");
    }

    /**
     * A broken promise is told to its Collection POC only while they can reach its region; otherwise the region's
     * promise managers hear instead, not whoever broke it (SC-17). Every notice carries the promise's region.
     */
    @Test
    void aBrokenPromiseGoesToItsRegionsManagersWhenItsPocHasLostTheRegion() throws Exception {
        User ada = user("ada.admin", "ADMIN");
        grantRepository.deleteAllOfUser(ada.getId());
        grant(ada, south, AccessLevel.MANAGE, RecordScope.ALL);
        Customer southCo = customer("South Co", south);
        actAs(admin);
        PromiseDtos.PromiseDto kept = promiseService.create(new PromiseDtos.CreatePromiseRequest(southCo.getId(),
                new BigDecimal("10.00"), Today.date().plusDays(2), collections.getId(), null, List.of()));
        PromiseDtos.PromiseDto lost = promiseService.create(new PromiseDtos.CreatePromiseRequest(southCo.getId(),
                new BigDecimal("20.00"), Today.date().plusDays(2), collections.getId(), null, List.of()));

        promiseService.override(kept.id(), PromiseStatus.BROKEN, "Refused to pay");
        assertThat(notificationRepository.findAll()).filteredOn(n -> n.getType().equals("PROMISE_BROKEN"))
                .singleElement().satisfies(n -> {
                    assertThat(n.getUserId()).isEqualTo(collections.getId());
                    assertThat(n.getRegionId()).isEqualTo(south.getId());
                });

        grantRepository.deleteAllOfUser(collections.getId());
        grant(collections, north, AccessLevel.READ, RecordScope.BOOK);
        promiseService.override(lost.id(), PromiseStatus.BROKEN, "Refused to pay");
        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType().equals("PROMISE_BROKEN") && n.getLink().equals("/promises/" + lost.id()))
                .as("ada manages SOUTH's promises; admin broke it; cara can no longer open it")
                .singleElement().satisfies(n -> {
                    assertThat(n.getUserId()).isEqualTo(ada.getId());
                    assertThat(n.getRegionId()).isEqualTo(south.getId());
                });
    }

    /**
     * The record's own POC holds its record-level role only while they can reach its region (§5.6.7): an invoice
     * whose Sales POC has lost SOUTH neither writes to them as the role nor sends as them.
     */
    @Test
    void anInvoicesOwnPocWithoutItsRegionIsNeitherEmailedNorTheSenderAsTheRole() throws Exception {
        Customer southCo = customer("South Co", south);
        Invoice inv = invoice(southCo, sid, 1);
        Map<String, Object> role = Map.of("type", "ROLE", "role", "SALES_POC", "level", "RECORD");
        Map<String, Object> asTheRole = Map.of("entityType", "INVOICE", "entityId", inv.getId(),
                "from", role, "to", List.of(role), "subject", "Your invoice", "body", "Hello");

        JsonNode holding = preview(asTheRole);
        assertThat(holding.get("to")).extracting(n -> n.get("userId").asLong()).containsExactly(sid.getId());
        assertThat(holding.at("/from/userId").asLong()).isEqualTo(sid.getId());
        assertThat(holding.get("unresolved")).isEmpty();

        grantRepository.deleteAllOfUser(sid.getId());
        grant(sid, north, AccessLevel.READ, RecordScope.BOOK);
        JsonNode lost = preview(asTheRole);
        assertThat(lost.get("to")).isEmpty();
        assertThat(lost.get("from").isNull()).as("nobody left to send as the role").isTrue();
        assertThat(lost.get("unresolved")).extracting(n -> n.get("token").asText())
                .containsExactly("ROLE:RECORD:SALES_POC");

        JsonNode sent = objectMapper.readTree(mockMvc.perform(post("/api/emails").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "entityType", "INVOICE", "entityId", inv.getId(),
                                "to", List.of(role, Map.of("type", "USER", "userId", admin.getId())),
                                "subject", "Your invoice", "body", "Hello"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(sent.get("to")).extracting(n -> n.get("userId").asLong()).containsExactly(admin.getId());
        assertThat(emailRecipientRepository.findAll()).extracting(r -> r.getUserId())
                .doesNotContain(sid.getId());
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getSalesPoc().getId())
                .as("the invoice keeps its POC").isEqualTo(sid.getId());
    }

    private JsonNode preview(Map<String, Object> request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/emails/preview").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * A new dispute is told to the active administrators who can reach its region, and to nobody else holding the
     * ADMIN role: not one who works only in NORTH, nor one who has been deactivated (§5.6.7).
     */
    @Test
    void aDisputeIsToldOnlyToActiveAdministratorsWhoHoldItsRegion() throws Exception {
        User ada = staff("ada.admin", "ADMIN", south, AccessLevel.MANAGE, RecordScope.ALL);
        User nick = staff("nick.admin", "ADMIN", north, AccessLevel.APPROVE, RecordScope.ALL);
        User ivy = staff("ivy.admin", "ADMIN", south, AccessLevel.APPROVE, RecordScope.ALL);
        ivy.setActive(false);
        userRepository.save(ivy);
        Customer southCo = customer("South Co", south);
        Invoice inv = invoice(southCo, sid, 1);

        actAs(customerUser("south.login", southCo.getId()));
        Long dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                inv.getId(), "Wrong quantity", null)).getId();

        List<Notification> told = notificationRepository.findAll().stream()
                .filter(n -> n.getType().equals("DISPUTE_OPENED")).toList();
        assertThat(told).extracting(Notification::getUserId)
                .contains(admin.getId(), ada.getId())
                .doesNotContain(nick.getId(), ivy.getId());
        assertThat(told).allSatisfy(n -> {
            assertThat(n.getRegionId()).isEqualTo(south.getId());
            assertThat(n.getLink()).isEqualTo("/disputes/" + dispute);
        });
    }

    /** A link too long to store is dropped, with the notice still saved: telling someone never fails the save. */
    @Test
    void aNoticeWhoseLinkIsTooLongIsKeptWithoutIt() {
        String tooLong = "/invoices/" + "9".repeat(Notification.LINK_MAX);
        Notification saved = notificationService.notify(admin.getId(), "TEST", "Title", "Message", tooLong,
                south.getId());
        Notification stored = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getLink()).isNull();
        assertThat(stored.getTitle()).isEqualTo("Title");
        assertThat(stored.getRegionId()).isEqualTo(south.getId());

        String longest = "/" + "9".repeat(Notification.LINK_MAX - 1);
        assertThat(notificationRepository.findById(notificationService.notify(admin.getId(), "TEST", "Title",
                "Message", longest, south.getId()).getId()).orElseThrow().getLink()).isEqualTo(longest);
    }

    private List<Map.Entry<String, Map<String, Object>>> creates(Customer customer, User salesPoc) {
        return List.of(
                Map.entry("/api/invoices", Map.of("customerId", customer.getId(), "salesPocUserId", salesPoc.getId(),
                        "items", List.of(Map.of("productId", widget.getId(), "quantity", 1)))),
                Map.entry("/api/payments", Map.of("customerId", customer.getId(), "amount", "10.00",
                        "collectionPocUserId", collections.getId())),
                Map.entry("/api/promises", Map.of("customerId", customer.getId(), "amount", "10.00",
                        "promisedDate", Today.date().plusDays(2).toString(),
                        "collectionPocUserId", collections.getId())));
    }
}
