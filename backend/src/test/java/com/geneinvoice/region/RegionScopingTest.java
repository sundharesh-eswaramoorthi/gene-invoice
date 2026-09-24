package com.geneinvoice.region;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The moment every read in the application acquires a region. These tests are written against the
 * funnel rather than against any one endpoint, because the whole claim of B1 is that the axis is a
 * property of the query and not of anybody remembering to pass it (B1).
 */
class RegionScopingTest extends IntegrationTestBase {

    static final Instant RAISED = Instant.parse("2026-03-01T09:00:00Z");

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PocService pocService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired AuditService auditService;
    @Autowired TableQueryExecutor queryExecutor;
    @Autowired RegionScope regionScope;
    @Autowired RegionController regionController;

    User admin;
    User sales;
    User cashier;
    Region north;
    Customer home;
    Customer away;
    Invoice homeInvoice;
    Invoice awayInvoice;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        // A branch opened AFTER the administrator's wildcard grant was written, which is the whole
        // point of a null region_id on a grant row (B1).
        north = region("NORTH");
        cashier = user("cara.cashier", "CASHIER");
        // The NORTH invoice's Sales POC has to work in NORTH: from R8 a per-record POC field is
        // refused for somebody who cannot MANAGE that branch. sam is never a caller in this class,
        // so nothing anybody can see changes (B1, R8).
        staffedAt(sales, north);
        widget = product("Widget", "100.00");
        home = customer("Home Ltd");
        away = customerRepository.save(Customer.builder().name("Away Ltd").region(north).build());
        actAs(admin);
        homeInvoice = invoice(home);
        awayInvoice = invoice(away);
    }

    /** One more branch this person may work in, on top of whatever their role already implies. */
    private void staffedAt(User u, Region where) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(where.getId()).right(RegionRight.MANAGE).build());
    }

    private Invoice invoice(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), RAISED, null,
                null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    @Test
    void aUserWithNoRightInARegionSeesNoneOfItsInvoices() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(homeInvoice.getId()));

        // And the tile agrees with the list, because aggregate() shares the same predicate list.
        mockMvc.perform(get("/api/invoices/summary").with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void anInvoiceInARegionTheCallerCannotSeeIsNotFoundRatherThanForbidden() throws Exception {
        mockMvc.perform(get("/api/invoices/" + awayInvoice.getId()).with(as(cashier)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/invoices/" + homeInvoice.getId()).with(as(cashier)))
                .andExpect(status().isOk());
    }

    @Test
    void scopeOverrideOpensTheBookButNeverTheRegion() throws Exception {
        // CASHIER holds SCOPE_OVERRIDE, so the POC book contributes no predicate at all and the
        // only thing narrowing this list is the region axis.
        actAs(cashier);
        assertThat(regionScope.lockedFilters(Invoice.class))
                .containsExactly("regionId:in:" + defaultRegion().getId());

        mockMvc.perform(get("/api/customers").with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(home.getId()));
    }

    @Test
    void theWildcardGrantCoversARegionCreatedAfterItWasGiven() throws Exception {
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .allSatisfy(g -> assertThat(g.getRegionId()).isNull());

        mockMvc.perform(get("/api/invoices").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // A wildcard holder adds no predicate at all, so the SQL is the one that ran before B1.
        actAs(admin);
        assertThat(regionScope.lockedFilters(Invoice.class)).isEmpty();
    }

    @Test
    void aCustomerLoginIsScopedByItsCustomerAndNotByRegionGrants() throws Exception {
        User login = customerUser("away.login", away.getId());
        assertThat(userRegionGrantRepository.findByUserId(login.getId())).isEmpty();

        mockMvc.perform(get("/api/invoices").with(as(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(awayInvoice.getId()));

        actAs(login);
        assertThat(regionScope.lockedFilters(Invoice.class)).isEmpty();
    }

    @Test
    void aSingleRecordReadIsRefusedWhenTheCallerHasNoOtherScope() {
        // The one line in inScope, pinned in both directions: an empty scope list no longer means
        // "everything" for a regional table, and still means "everything" for an unregioned one.
        actAs(cashier);
        assertThat(queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES,
                awayInvoice.getId(), List.of())).isFalse();
        assertThat(queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES,
                homeInvoice.getId(), List.of())).isTrue();
        assertThat(queryExecutor.inScope(Product.class, TableSchemas.PRODUCTS,
                widget.getId(), List.of())).isTrue();
    }

    @Test
    void aCallerWithNoGrantAtAllReadsNothingAndIsToldSoByAChip() throws Exception {
        User stranger = user("sid.stranger", "CASHIER");
        revokeRegionGrants(stranger);

        mockMvc.perform(get("/api/invoices").with(as(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        actAs(stranger);
        assertThat(regionScope.lockedFilters(Invoice.class))
                .containsExactly(RegionScope.NO_REGION_FILTER);
    }

    @Test
    void anUnregionedTableIsUntouchedByRegions() throws Exception {
        assertThat(RegionAxes.of(Product.class)).isEqualTo(RegionAxis.NONE);
        assertThat(RegionAxes.reason(Product.class)).isNotBlank();

        mockMvc.perform(get("/api/products").with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        actAs(cashier);
        assertThat(regionScope.lockedFilters(Product.class)).isEmpty();
    }

    @Test
    void aPersonIsVisibleWhereTheyWorkAndAPersonWithNoGrantIsVisibleToTheAdminWhoMadeThem()
            throws Exception {
        User elsewhere = user("nina.north", "CASHIER");
        revokeRegionGrants(elsewhere);
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(elsewhere.getId()).regionId(north.getId()).right(RegionRight.MANAGE).build());
        User unplaced = user("una.unplaced", "CASHIER");
        revokeRegionGrants(unplaced);

        actAs(cashier);
        List<Long> visible = queryExecutor.ids(User.class, TableSchemas.USERS,
                TableQuery.parseUnpaged(TableSchemas.USERS, null, List.of()), List.of(), 100);

        assertThat(visible).contains(cashier.getId(), unplaced.getId());
        assertThat(visible).doesNotContain(elsewhere.getId());
    }

    @Test
    void aSystemThreadSeesEveryRegionAndAnAutomationRunSeesOnlyItsOwn() {
        SecurityContextHolder.clearContext();
        // No principal and no hatch: "everything" is the one answer that is certainly wrong.
        assertThat(count()).isZero();

        assertThat(RegionScope.asSystem(RegionScope.SystemReason.PROMISE_SWEEP, this::count))
                .isEqualTo(2);
        assertThat(RegionScope.asRegions(Set.of(north.getId()),
                RegionScope.SystemReason.AUTOMATION_FANOUT, this::count)).isEqualTo(1);
        // A rule whose author holds no region reaches nothing, rather than everything (A5, B1).
        assertThat(RegionScope.asRegions(Set.of(),
                RegionScope.SystemReason.AUTOMATION_ACT, this::count)).isZero();

        // The hatch is unwound even when the body throws, or the next request on this thread would
        // inherit it.
        assertThatThrownBy(() -> RegionScope.asSystem(RegionScope.SystemReason.DATA_SEED,
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    private long count() {
        return queryExecutor.count(Invoice.class, TableSchemas.INVOICES,
                TableQuery.parseUnpaged(TableSchemas.INVOICES, null, List.of()), List.of());
    }

    @Test
    void aSchemaUsedOnTheWrongRootIsRefusedRatherThanScopedByTheWrongRule() {
        actAs(admin);
        assertThatThrownBy(() -> queryExecutor.count(Product.class, TableSchemas.INVOICES,
                TableQuery.parseUnpaged(TableSchemas.INVOICES, null, List.of()), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoices")
                .hasMessageContaining("Product");
    }

    @Test
    void anEntityWithNoDeclaredAxisFailsLoudlyRatherThanReadingAsVisibleEverywhere() {
        assertThatThrownBy(() -> RegionAxes.of(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No region axis declared");
    }

    // ---------------------------------------------------------------------------------------
    // R5-REGION-PRESENTATION: every list SAYS which regions it covers, the region map is a table
    // of its own, and the region selector's wire format exists.
    // ---------------------------------------------------------------------------------------

    /**
     * A reader who may see every regional list, so one caller can be asked all five questions.
     * Deliberately WITHOUT POC_VIEW: the roster endpoint must not be reachable only by people who
     * can also see the POC book, which is why GET /api/pocs/my-scope cannot serve it (B1).
     */
    private User regionReader() {
        Role role = roleRepository.findByName("REGION_READER").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("REGION_READER")
                        .description("Reads every regional list in one region")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                                        Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW,
                                        Privileges.DISPUTE_VIEW, Privileges.PRODUCT_VIEW,
                                        Privileges.EXPORT_DATA, Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return user("rita.reader", role.getName());
    }

    @Test
    void everyListSaysWhichRegionsItCovers() throws Exception {
        User reader = regionReader();
        String chip = "regionId:in:" + defaultRegion().getId();

        for (String path : List.of("/api/customers", "/api/invoices", "/api/payments",
                "/api/promises", "/api/disputes")) {
            mockMvc.perform(get(path).with(as(reader)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lockedFilters", hasItem(chip)));
        }

        // An unregioned list says nothing, because there is nothing to say: the payload of a
        // products page is byte-identical to the one that shipped before B1.
        mockMvc.perform(get("/api/products").with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFilters").isEmpty());

        // And the other end of the scale: somebody who can see no region is TOLD so, rather than
        // being left to wonder why an empty page is empty.
        User stranger = user("stan.stranger", "CASHIER");
        revokeRegionGrants(stranger);
        mockMvc.perform(get("/api/invoices").with(as(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.lockedFilters", hasItem(RegionScope.NO_REGION_FILTER)));
    }

    @Test
    void aWildcardHolderGetsNoRegionChipAndTheSameSqlAsBefore() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.lockedFilters").isEmpty());

        // Not merely "no chip": no clause either. CountingStatements matches on the raw SQL, so
        // counting the selects that mention region_id is a direct reading of what was sent to the
        // database. A wildcard holder adds nothing; a caller with one region adds exactly one.
        actAs(admin);
        assertThat(CountingStatements.reads("region_id", this::count)).isZero();
        actAs(cashier);
        assertThat(CountingStatements.reads("region_id", this::count)).isEqualTo(1);
    }

    @Test
    void theCsvExportContainsOnlyTheRegionsTheCallerCanSee() throws Exception {
        String body = json(new BulkDtos.BulkRequest("EXPORT", null, true, null, null, null));

        String mine = mockMvc.perform(post("/api/invoices/export").with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).contains("Home Ltd").doesNotContain("Away Ltd");

        // The export reads the same funnel as the list, so the administrator's copy has both rows
        // and nothing about the export endpoint itself had to learn about regions.
        String everything = mockMvc.perform(post("/api/invoices/export").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(everything).contains("Home Ltd").contains("Away Ltd");
    }

    @Test
    void askingForARegionYouCannotSeeReturnsNothingRatherThanForbidding() throws Exception {
        // A user-supplied region filter can only ever narrow: the mandatory predicate is ANDed in
        // regardless, so a region you cannot see reads exactly like one that does not exist,
        // which is AUTH-08 and is never relaxed.
        mockMvc.perform(get("/api/invoices?filter=regionId:in:" + north.getId()).with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/customers?filter=regionId:eq:" + north.getId()).with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // A region that does not exist at all reads the same way, for the same caller.
        mockMvc.perform(get("/api/invoices?filter=regionId:in:999999").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // And the filter really does filter, rather than being ignored: the administrator asking
        // for NORTH gets the NORTH invoice and only that one.
        mockMvc.perform(get("/api/invoices?filter=regionId:in:" + north.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(awayInvoice.getId()));
    }

    @Test
    void theRegionColumnFiltersOnEveryTableThatHasOneIncludingTheOneWithNoAssociationToWalk()
            throws Exception {
        User collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        // Holds a seat in both branches, so she works in both: a seat is refused in a branch its
        // holder cannot MANAGE (B1, R8).
        staffedAt(collections, north);
        pocService.add(home.getId(), PocType.COLLECTION, collections.getId(), true);
        pocService.add(away.getId(), PocType.COLLECTION, collections.getId(), true);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(home.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), collections.getId(), null));
        promiseService.create(new PromiseDtos.CreatePromiseRequest(away.getId(),
                new BigDecimal("50.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                collections.getId(), null, List.of()));
        disputeRepository.save(Dispute.builder()
                .customerId(away.getId()).openedByUserId(admin.getId())
                .targetType(DisputeTargetType.INVOICE).targetId(awayInvoice.getId())
                .reason("raised in the north").build());

        Long home_ = defaultRegion().getId();
        // A dispute keeps a bare customer_id with no association to walk, so its regionId column
        // is a custom EXISTS; the other four resolve a path. Both have to answer the same way.
        mockMvc.perform(get("/api/payments?filter=regionId:in:" + home_).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/promises?filter=regionId:in:" + home_).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/promises?filter=regionId:in:" + north.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/disputes?filter=regionId:in:" + north.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/disputes?filter=regionId:in:" + home_).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // The hand-written filter narrows the same way for a caller who cannot see NORTH: the
        // mandatory predicate is ANDed in, so the answer is empty rather than forbidden.
        mockMvc.perform(get("/api/disputes?filter=regionId:in:" + north.getId()).with(as(regionReader())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void everyRegionalRowSaysWhichRegionItIsIn() throws Exception {
        User collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(home.getId(), PocType.COLLECTION, collections.getId(), true);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(home.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), collections.getId(), null));
        promiseService.create(new PromiseDtos.CreatePromiseRequest(home.getId(),
                new BigDecimal("50.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                collections.getId(), null, List.of()));

        Long id = defaultRegion().getId();
        String name = defaultRegion().getName();
        for (String path : List.of("/api/customers", "/api/invoices", "/api/payments",
                "/api/promises")) {
            mockMvc.perform(get(path + "?filter=regionId:in:" + id).with(as(cashier)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].regionId").value(id))
                    .andExpect(jsonPath("$.content[0].regionName").value(name));
        }

        // And the column is real enough to sort by, which the two-hop LEFT join is what makes
        // possible: an INNER join here would drop every row whose customer is unplaced.
        mockMvc.perform(get("/api/invoices?sort=regionName,asc").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // The single-record read carries it too, so a screen that never lists does not have to
        // guess which region the record it is showing belongs to.
        mockMvc.perform(get("/api/invoices/" + homeInvoice.getId()).with(as(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(id))
                .andExpect(jsonPath("$.regionName").value(name));
    }

    @Test
    void theRegionMapIsPublishedAsItsOwnTableAndIsNotItselfRegionScoped() throws Exception {
        // Registered through SchemaRegistry rather than by another edit to TableSchemas.
        assertThat(TableSchemas.entities()).contains("regions");
        assertThat(TableSchemas.byEntity("regions")).isSameAs(RegionSchemas.REGIONS);

        mockMvc.perform(get("/api/table-schemas/regions").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("regions"))
                .andExpect(jsonPath("$.columns[?(@.name=='code')]").exists());

        // REGION_VIEW is company-wide: somebody who may see the map sees all of it, and the page
        // carries no region chip because Region is classified NONE ("the region map itself").
        mockMvc.perform(get("/api/regions").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.lockedFilters").isEmpty());

        // A cashier works in a region but may not read the map.
        mockMvc.perform(get("/api/regions").with(as(cashier)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRosterNamesWhereTheCallerMayWorkWithoutNeedingThePocBook() throws Exception {
        User reader = regionReader();
        mockMvc.perform(get("/api/regions/my").with(as(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions[0].code").value(defaultRegion().getCode()))
                .andExpect(jsonPath("$.regions[0].rights[0]").value("VIEW"));

        // The wildcard is reported separately and is deliberately NOT expanded: listing every
        // region a wildcard holder can reach would be a query per sign-in and would go stale the
        // day a branch is opened.
        mockMvc.perform(get("/api/regions/my").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(true))
                .andExpect(jsonPath("$.regions").isEmpty());

        User stranger = user("sue.stranger", "CASHIER");
        revokeRegionGrants(stranger);
        mockMvc.perform(get("/api/regions/my").with(as(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void aRegionIsOpenedAndRetiredByNameAndItsCodeIsOnlyEverOneBranch() throws Exception {
        mockMvc.perform(post("/api/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.RegionUpsert("west", "West Branch", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WEST"))
                .andExpect(jsonPath("$.active").value(true));

        Region west = regionRepository.findByCode("WEST").orElseThrow();

        // "north" and "NORTH" are one branch: uk_region_code is an exact-match constraint that
        // would happily hold both, so the code is normalised before it is checked.
        mockMvc.perform(post("/api/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.RegionUpsert("North", "Another north", null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/regions/" + west.getId()).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.RegionUpsert("WEST", "West Region", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("West Region"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/regions").with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.RegionUpsert("EAST", "East Branch", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void replacingSomebodysRegionsTakesEffectOnTheirNextReadAndLeavesATrail() throws Exception {
        // cara.cashier reads only the default region today.
        mockMvc.perform(get("/api/invoices").with(as(cashier)))
                .andExpect(jsonPath("$.totalElements").value(1));

        String body = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(north.getId(), "manage"))));
        mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(false))
                .andExpect(jsonPath("$.regions[0].code").value("NORTH"));

        // Replace, not add: the grant they held in the default region is gone, so the list they
        // saw a moment ago is now the other one.
        mockMvc.perform(get("/api/invoices").with(as(cashier)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(awayInvoice.getId()))
                .andExpect(jsonPath("$.lockedFilters", hasItem("regionId:in:" + north.getId())));

        // Naming a region that does not exist is not found, exactly as a missing record is
        // everywhere else, and it changes nothing.
        mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(999999L, "VIEW"))))))
                .andExpect(status().isNotFound());
        assertThat(userRegionGrantRepository.findByUserId(cashier.getId())).hasSize(1);

        // The trail names the wildcard explicitly, because somebody being given or losing it is
        // the change a reviewer most needs to see.
        mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(null, "VIEW"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allRegions").value(true));
        assertThat(auditService.historyFor("USER", cashier.getId()))
                .anySatisfy(row -> {
                    assertThat(row.getAction()).isEqualTo("USER_REGIONS_CHANGED");
                    assertThat(row.getAfterJson()).contains("\"code\":\"*\"");
                });

        // Editing somebody's regions needs BOTH privileges; a cashier holds neither.
        mockMvc.perform(put("/api/users/" + admin.getId() + "/regions").with(as(cashier))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    /**
     * THE LAST ACCOUNT THAT CAN APPROVE IN EVERY BRANCH CANNOT BE STRIPPED OF THAT (B1, B2).
     *
     * <p>PUT /api/users/{id}/regions REPLACES the whole set, so a body that simply omits the
     * wildcard APPROVE row deletes it — and the editor that composes the body cannot express two
     * rights for one branch, which makes the omission the easy accident rather than a decision.
     * Lose the last one and B2's queue has no approver of last resort: APPROVE is not covered by
     * MANAGE, and requireNotSelfWidening stops any survivor handing it back to themselves, so the
     * installation is locked out of its own four-eyes gate with nothing short of SQL to undo it.
     *
     * <p>403 and not 400, because the refusal is about authority and not about the shape of the
     * request, and it is the shape every other region refusal in this build takes (D-46).
     */
    @Test
    void theLastEveryBranchApproverCannotBeTakenAwayAndTheGrantsDoNotMove() throws Exception {
        // The seeded administrator is the only account holding APPROVE in every branch: sam and
        // cara are staffed into NAMED regions by the fixture.
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(null, RegionRight.MANAGE), tuple(null, RegionRight.APPROVE));

        String manageOnly = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(null, "MANAGE"))));
        mockMvc.perform(put("/api/users/" + admin.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(manageOnly))
                .andExpect(status().isForbidden());

        // AND NOTHING MOVED. The guard runs inside the transaction and before the first delete,
        // so a refusal is not half a replacement.
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(null, RegionRight.MANAGE), tuple(null, RegionRight.APPROVE));

        // The sentence a person can act on, which the 403 body deliberately does not carry: the
        // handler answers every AccessDeniedException with one generic line, so the only place
        // this text is readable is the exception itself.
        actAs(admin);
        assertThatThrownBy(() -> regionController.setGrants(admin.getId(),
                new RegionController.SetGrantsRequest(List.of(
                        new RegionController.GrantInput(null, "MANAGE")))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("last active account that can approve in every branch");

        // A SECOND ADMINISTRATOR, and the same edit goes through: the guard counts holders, it
        // does not freeze one account's grants for ever.
        User deputy = user("dee.deputy", "ADMIN");
        assertThat(userRegionGrantRepository.findByUserId(deputy.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .contains(tuple(null, RegionRight.APPROVE));

        mockMvc.perform(put("/api/users/" + admin.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(manageOnly))
                .andExpect(status().isOk());
        assertThat(userRegionGrantRepository.findByUserId(admin.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactly(tuple(null, RegionRight.MANAGE));
    }

    /**
     * NOBODY HANDS THEMSELVES THE COMPANY (B1, B2, D-46).
     *
     * <p>USER_MANAGE and REGION_MANAGE together are enough to edit ANY person's grants, including
     * the editor's own, and a null-region row is every branch there is and every branch there will
     * ever be. Without this guard a person trusted with one branch writes themselves one row and
     * is trusted with all of them, on nobody's say-so but their own. Widening somebody ELSE is
     * untouched, because that is two people by construction.
     *
     * <p>The test is the LADDER and not set membership: MANAGE covers VIEW, so a wildcard MANAGE
     * holder writing themselves wildcard VIEW gains nothing and goes through; MANAGE does not
     * cover APPROVE, so writing themselves wildcard APPROVE is a widening and is refused.
     */
    @Test
    void anAdministratorCannotGiveThemselvesAWildcardTheyDoNotAlreadyHold() throws Exception {
        User deputy = user("dee.deputy", "ADMIN");
        // Staffed into ONE branch, which is the posture the guard is about: they administer
        // people company-wide (USER_MANAGE and REGION_MANAGE are company-wide privileges) and
        // work in one branch.
        revokeRegionGrants(deputy);
        staffedAt(deputy, defaultRegion());

        String wildcardManage = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(null, "MANAGE"))));
        mockMvc.perform(put("/api/users/" + deputy.getId() + "/regions").with(as(deputy))
                        .contentType(MediaType.APPLICATION_JSON).content(wildcardManage))
                .andExpect(status().isForbidden());
        assertThat(userRegionGrantRepository.findByUserId(deputy.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactly(tuple(defaultRegion().getId(), RegionRight.MANAGE));

        actAs(deputy);
        assertThatThrownBy(() -> regionController.setGrants(deputy.getId(),
                new RegionController.SetGrantsRequest(List.of(
                        new RegionController.GrantInput(null, "MANAGE")))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cannot give yourself manage in every branch");

        // SOMEBODY ELSE MAY STILL DO IT, which is the whole difference the guard draws.
        mockMvc.perform(put("/api/users/" + deputy.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(wildcardManage))
                .andExpect(status().isOk());

        // Re-saving a wildcard they now DO hold is not a widening, and neither is adding a right
        // the one they hold already covers.
        mockMvc.perform(put("/api/users/" + deputy.getId() + "/regions").with(as(deputy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(null, "MANAGE"),
                                new RegionController.GrantInput(null, "VIEW"))))))
                .andExpect(status().isOk());

        // APPROVE is not covered by MANAGE, so asking for it is a widening however it is spelled.
        mockMvc.perform(put("/api/users/" + deputy.getId() + "/regions").with(as(deputy))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(null, "MANAGE"),
                                new RegionController.GrantInput(null, "APPROVE"))))))
                .andExpect(status().isForbidden());
        assertThat(userRegionGrantRepository.findByUserId(deputy.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(null, RegionRight.MANAGE), tuple(null, RegionRight.VIEW));
    }

    /**
     * Two rights in ONE region, which the ladder requires: MANAGE does not cover APPROVE and
     * APPROVE does not cover MANAGE, so anyone who must both work and approve in a branch holds
     * two rows there (RegionRight), RegionGrants keys a SET of rights per region, and B2's checker
     * is the person this shape exists for.
     *
     * <p>The reason this is a test and not an obvious truth: uk_grant_region was first written as
     * a partial unique index on (user_id, region_id) alone, which made this exact save answer 409
     * on Postgres — and H2 has no partial index, so nothing in this suite could see it. The index
     * now carries right_level; this is the behaviour it must never forbid again (B1).
     */
    @Test
    void somebodyCanHoldBothManageAndApproveInTheSameRegion() throws Exception {
        mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionController.SetGrantsRequest(List.of(
                                new RegionController.GrantInput(north.getId(), "MANAGE"),
                                new RegionController.GrantInput(north.getId(), "APPROVE"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions", hasSize(1)))
                .andExpect(jsonPath("$.regions[0].code").value("NORTH"))
                // Reported in the ladder's own order, so the payload is stable.
                .andExpect(jsonPath("$.regions[0].rights[0]").value("MANAGE"))
                .andExpect(jsonPath("$.regions[0].rights[1]").value("APPROVE"));

        assertThat(userRegionGrantRepository.findByUserId(cashier.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(north.getId(), RegionRight.MANAGE),
                        tuple(north.getId(), RegionRight.APPROVE));
    }

    /**
     * NO DUPLICATE GRANT ROW, which is the other half of what uk_grant_region says and the half
     * only the product can break.
     *
     * <p>The index is a PARTIAL unique index on
     * {@code user_region_grants (user_id, region_id, right_level) where region_id is not null},
     * so a second identical row is refused by Postgres and silently accepted by H2, which has no
     * partial index. setGrants is a DIFF and not a delete-and-recreate, so saving the same grants
     * again must add nothing at all. On H2 a duplicate row is merely harmless — RegionGrants keys
     * a SET — which is why only a row count can see it there; on Postgres the same edit would
     * answer 409. This test fails on BOTH dialects if the diff is ever lost (B1).
     */
    @Test
    void sayingTheSameThingToTheGrantEditorTwiceWritesNoSecondRow() throws Exception {
        String body = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(north.getId(), "MANAGE"),
                new RegionController.GrantInput(north.getId(), "APPROVE"))));
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        // Three identical saves, two rows. A count and not a set, so a duplicate shows.
        assertThat(userRegionGrantRepository.findByUserId(cashier.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(north.getId(), RegionRight.MANAGE),
                        tuple(north.getId(), RegionRight.APPROVE));

        // The same again for the WILDCARD, which is the other partial index (uk_grant_all) and the
        // one an administrator holds two rows of.
        String wildcard = json(new RegionController.SetGrantsRequest(List.of(
                new RegionController.GrantInput(null, "MANAGE"),
                new RegionController.GrantInput(null, "APPROVE"))));
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(put("/api/users/" + cashier.getId() + "/regions").with(as(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(wildcard))
                    .andExpect(status().isOk());
        }
        assertThat(userRegionGrantRepository.findByUserId(cashier.getId()))
                .extracting(UserRegionGrant::getRegionId, UserRegionGrant::getRight)
                .containsExactlyInAnyOrder(
                        tuple(null, RegionRight.MANAGE),
                        tuple(null, RegionRight.APPROVE));
    }
}
