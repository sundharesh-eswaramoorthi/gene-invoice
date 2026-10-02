package com.geneinvoice.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The principal carries its rights per region, read once when it is built, and everything that decides scope
 * asks it rather than the database (C-4, R3).
 */
class PrincipalRightsTest extends IntegrationTestBase {

    @Autowired JwtService jwtService;
    @Autowired ScopeResolver scopeResolver;
    @Autowired CurrentUser currentUser;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    User sales;
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
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        widget = product("Widget", "100.00");
        actAs(admin);
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

    /** A POC is assignable only where they hold a grant (§5.6.7), so the shared POCs also work in these regions. */
    private void pocsAlsoIn(Region... regions) {
        for (Region r : regions) {
            grant(sales, r, AccessLevel.READ, RecordScope.BOOK);
            grant(collections, r, AccessLevel.READ, RecordScope.BOOK);
        }
    }

    /**
     * A real token, so the request builds its principal the way production does (JwtAuthFilter). The test
     * thread's own principal is dropped: the filter authenticates only a request that has none yet.
     */
    private String bearer(User u) {
        User fresh = userRepository.findById(u.getId()).orElseThrow();
        String token = "Bearer " + jwtService.generateToken(fresh.getUsername(), jwtService.claimsFor(fresh));
        SecurityContextHolder.clearContext();
        return token;
    }

    private Invoice invoice(Customer customer, User salesPoc) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Long regionOf(Long customerId) {
        return customerRepository.regionIdOf(customerId).orElse(null);
    }

    private Map<String, Object> newCustomer(String name, Object... extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("username", name.toLowerCase().replace(' ', '.'));
        body.put("password", "Demo1234!");
        for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
        return body;
    }

    @Test
    void aSignedInRequestReadsTheCallersGrantsExactlyOnce() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        pocsAlsoIn(north);
        invoice(customer("North Co", north), sales);
        String token = bearer(rita);

        long reads = CountingStatements.reads("user_region_grants", () ->
                mockMvc.perform(get("/api/invoices").header("Authorization", token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(1)));

        assertThat(reads).isEqualTo(1);
    }

    @Test
    void decidingScopeCostsNoSqlAtAll() throws Exception {
        User lead = staff("lena.lead", DataSeeder.ROLE_SALES_POC, north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.READ, RecordScope.BOOK);
        actAs(lead);

        long roleReads = CountingStatements.reads("role_privileges", this::askEverything);
        long anyReads = CountingStatements.reads("", this::askEverything);

        assertThat(roleReads).isZero();
        assertThat(anyReads).as("scope is decided in memory; a list pays only for its own query").isZero();
    }

    private void askEverything() {
        scopeResolver.forCustomers();
        scopeResolver.forInvoices();
        scopeResolver.forPayments();
        scopeResolver.forPromises();
        scopeResolver.forDisputes();
        scopeResolver.canSeePoc();
        scopeResolver.canSeeEverything();
        scopeResolver.isAssignableAs(PocType.SALES);
        scopeResolver.isAssignableIn(PocType.SALES, north.getId());
        scopeResolver.tier(ScopeResolver.Kind.INVOICE, ScopeResolver.Access.MANAGE, south.getId());
        currentUser.hasIn(Privileges.INVOICE_MANAGE, north.getId());
        currentUser.canAssignPocAnywhere();
    }

    /** Email and the inbox run without an open session (OEMIV is off there), so the principal is detached. */
    @Test
    void emailAndTheInboxAnswerAUserWithSeveralRegionsOnPathsWithoutAnOpenSession() throws Exception {
        User maya = user("maya", "CASHIER");
        grant(maya, north, AccessLevel.READ, RecordScope.ALL);
        Customer acme = customer("Acme Ltd");
        String token = bearer(maya);

        mockMvc.perform(get("/api/emails").header("Authorization", token)
                        .param("entityType", "CUSTOMER").param("entityId", acme.getId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inbox").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inbox/unread-count").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void withoutAPrincipalScopeRefusesAndEveryRegionCheckFailsClosed() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> scopeResolver.forInvoices()).isInstanceOf(BadCredentialsException.class);
        assertThat(currentUser.hasIn(Privileges.INVOICE_VIEW, defaultRegion().getId())).isFalse();
        assertThat(currentUser.rights()).isSameAs(RegionRights.none());
        assertThat(currentUser.regionsWith(Privileges.INVOICE_VIEW)).isEmpty();
    }

    @Test
    void meListsTheUnionOfPrivilegesAndWhatHoldsInEachRegion() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.BOOK);

        JsonNode me = read(mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(rita)))
                .andExpect(status().isOk()));

        assertThat(me.get("privileges")).extracting(JsonNode::asText)
                .contains(Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE, Privileges.SCOPE_OVERRIDE,
                        Privileges.PRODUCT_VIEW, Privileges.EXPORT_DATA);
        assertThat(me.get("regions")).hasSize(2);
        JsonNode first = me.get("regions").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(north.getId());
        assertThat(first.get("code").asText()).isEqualTo("NORTH");
        assertThat(first.get("name").asText()).isEqualTo("North");
        assertThat(first.get("active").asBoolean()).isTrue();
        assertThat(first.get("defaultRegion").asBoolean()).isFalse();
        assertThat(first.get("level").asText()).isEqualTo("READ");
        assertThat(first.get("scope").asText()).isEqualTo("ALL");
        assertThat(first.get("privileges")).extracting(JsonNode::asText)
                .contains(Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE)
                .doesNotContain(Privileges.INVOICE_MANAGE, Privileges.PRODUCT_VIEW);
        JsonNode second = me.get("regions").get(1);
        assertThat(second.get("code").asText()).isEqualTo("SOUTH");
        assertThat(second.get("level").asText()).isEqualTo("MANAGE");
        assertThat(second.get("scope").asText()).isEqualTo("BOOK");
        assertThat(second.get("privileges")).extracting(JsonNode::asText)
                .contains(Privileges.INVOICE_MANAGE)
                .as("a BOOK grant never overrides the book")
                .doesNotContain(Privileges.SCOPE_OVERRIDE);
    }

    @Test
    void signingInReturnsTheSameShape() throws Exception {
        staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL);

        JsonNode login = read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "rita", "password", "password"))))
                .andExpect(status().isOk()));

        assertThat(login.at("/user/regions")).hasSize(1);
        assertThat(login.at("/user/regions/0/code").asText()).isEqualTo("NORTH");
        assertThat(login.at("/user/privileges")).extracting(JsonNode::asText)
                .contains(Privileges.INVOICE_VIEW)
                .as("nothing CASHIER manages is held anywhere at READ")
                .doesNotContain(Privileges.INVOICE_MANAGE, Privileges.CUSTOMER_MANAGE);
    }

    @Test
    void aCustomerLoginListsNoRegionsAndKeepsItsRolesPrivileges() throws Exception {
        Customer acme = customer("Acme Ltd", north);
        User acmeLogin = customerUser("acme.login", acme.getId());

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(acmeLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions", empty()))
                .andExpect(jsonPath("$.customerId").value(acme.getId().intValue()))
                .andExpect(jsonPath("$.privileges", org.hamcrest.Matchers.hasItems(
                        Privileges.INVOICE_VIEW, Privileges.DISPUTE_CREATE)));
    }

    @Test
    void aPrivilegeHeldNowhereAtItsLevelIsRefusedByTheEndpointGate() throws Exception {
        User reader = staff("rhea", "CASHIER", north, AccessLevel.READ, RecordScope.ALL);

        mockMvc.perform(get("/api/customers").with(as(reader))).andExpect(status().isOk());
        mockMvc.perform(post("/api/customers").with(as(reader)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(newCustomer("Nope Ltd"))))
                .andExpect(status().isForbidden());

        assertThat(customerRepository.findAll()).isEmpty();
    }

    @Test
    void aUserCreatedWithoutGrantsHoldsNoRegionUntilOneIsGiven() throws Exception {
        Long roleId = role(DataSeeder.ROLE_SALES_POC).getId();

        JsonNode created = read(mockMvc.perform(post("/api/users").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "nia.new", "password", "Demo1234!", "roleId", roleId))))
                .andExpect(status().isOk()));

        long id = created.get("id").asLong();
        assertThat(grantRepository.findWithRegionByUserId(id))
                .as("access is given on purpose, never by default (SC-5)").isEmpty();
        assertThat(created.get("regionGrants")).hasSize(0);
        assertThat(userRepository.findById(id).orElseThrow().isRegionBackfillPending())
                .as("an account saved now is never taken for one from before regions").isFalse();
        mockMvc.perform(get("/api/auth/me").with(as(userRepository.findById(id).orElseThrow())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions", empty()))
                .andExpect(jsonPath("$.privileges", contains(Privileges.AUTOMATION_MANAGE,
                        Privileges.AUTOMATION_VIEW, Privileges.EXPORT_DATA, Privileges.NOTIFICATION_VIEW,
                        Privileges.PRODUCT_VIEW)));
    }

    @Test
    void aUserWhoseGrantCannotBeGivenIsNotCreatedEither() throws Exception {
        Long roleId = role(DataSeeder.ROLE_SALES_POC).getId();
        Region east = region("EAST");

        mockMvc.perform(post("/api/users").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "nia.new", "password", "Demo1234!", "roleId", roleId,
                                "regionGrants", List.of(
                                        Map.of("regionId", north.getId(), "level", "READ", "scope", "ALL"),
                                        Map.of("regionId", east.getId(), "level", "READ", "scope", "ALL"))))))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByUsername("nia.new"))
                .as("an account without its grants could see nothing, and a retry would be told it exists")
                .isEmpty();
        assertThat(grantRepository.countByRegionId(north.getId())).isEqualTo(1);
    }

    @Test
    void aListAnswersTheRegionsGrantedAndAskedFor() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);
        Region west = region("WEST");
        customer("Default Co");
        customer("North Co", north);
        customer("South Co", south);

        mockMvc.perform(get("/api/customers").with(as(rita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.regionIds", contains(north.getId().intValue(), south.getId().intValue())))
                .andExpect(jsonPath("$.bookRegionIds", empty()))
                .andExpect(jsonPath("$.lockedFilters", empty()));
        mockMvc.perform(get("/api/customers").with(as(rita)).param("regionIds", north.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("North Co"))
                .andExpect(jsonPath("$.regionIds", contains(north.getId().intValue())));
        mockMvc.perform(get("/api/customers").with(as(rita)).param("regionIds", west.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.regionIds", empty()))
                .andExpect(jsonPath("$.lockedFilters", empty()));
    }

    @Test
    void aRecordInARegionTheCallerHoldsNothingInIsNotFoundWhateverRegionsAreAsked() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL);
        pocsAlsoIn(north);
        Customer home = customer("Default Co");
        Customer northCo = customer("North Co", north);
        Invoice homeInvoice = invoice(home, sales);
        Invoice northInvoice = invoice(northCo, sales);

        mockMvc.perform(get("/api/customers/" + home.getId()).with(as(rita))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/" + homeInvoice.getId()).with(as(rita))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments/credits/" + home.getId()).with(as(rita)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customers/" + northCo.getId()).with(as(rita))
                        .param("regionIds", south.getId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/invoices/" + northInvoice.getId()).with(as(rita)))
                .andExpect(status().isOk());
    }

    @Test
    void aBookGrantShowsOnlyTheCallersOwnRecordsInThatRegion() throws Exception {
        Role lead = roleRepository.save(Role.builder()
                .name("SALES_LEAD")
                .description("Sales POC who may see a whole region")
                .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                                Privileges.POC_VIEW, Privileges.POC_ASSIGNABLE_SALES, Privileges.SCOPE_OVERRIDE)
                        .map(n -> privilegeRepository.findByName(n).orElseThrow())
                        .collect(Collectors.toCollection(HashSet::new)))
                .build());
        User lena = staff("lena.lead", lead.getName(), north, AccessLevel.MANAGE, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.BOOK);
        User sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        grant(sid, north, AccessLevel.READ, RecordScope.BOOK);
        grant(sid, south, AccessLevel.READ, RecordScope.BOOK);
        Customer northCo = customer("North Co", north);
        Customer southCo = customer("South Co", south);
        Invoice northOther = invoice(northCo, sid);
        Invoice southMine = invoice(southCo, lena);
        Invoice southOther = invoice(southCo, sid);

        JsonNode page = read(mockMvc.perform(get("/api/invoices").with(as(lena))).andExpect(status().isOk()));

        assertThat(page.get("content")).extracting(n -> n.get("id").asLong())
                .containsExactlyInAnyOrder(northOther.getId(), southMine.getId());
        assertThat(page.get("lockedFilters")).as("a mixed view carries no book chip").isEmpty();
        assertThat(page.get("bookRegionIds")).extracting(JsonNode::asLong).containsExactly(south.getId());
        mockMvc.perform(get("/api/invoices/" + southOther.getId()).with(as(lena))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/" + northOther.getId()).with(as(lena))).andExpect(status().isOk());

        mockMvc.perform(get("/api/invoices").with(as(lena)).param("regionIds", south.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.lockedFilters", contains("salesPocUserId:eq:" + lena.getId())));
    }

    @Test
    void aPureBookViewKeepsTodaysChip() throws Exception {
        User sid = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        Customer acme = customer("Acme Ltd");
        Invoice mine = invoice(acme, sales);
        invoice(acme, sid);

        mockMvc.perform(get("/api/invoices").with(as(sales)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mine.getId().intValue()))
                .andExpect(jsonPath("$.lockedFilters", contains("salesPocUserId:eq:" + sales.getId())))
                .andExpect(jsonPath("$.regionIds", contains(defaultRegion().getId().intValue())))
                .andExpect(jsonPath("$.bookRegionIds", contains(defaultRegion().getId().intValue())));
    }

    @Test
    void recordsOnACustomerTakeItsRegion() {
        pocsAlsoIn(north);
        Customer northCo = customer("North Co", north);
        User northLogin = customerUser("north.login", northCo.getId());

        Invoice inv = invoice(northCo, sales);
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(northCo.getId(),
                new BigDecimal("40.00"), "Cash", null, List.of(inv.getId()), collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(northCo.getId(),
                new BigDecimal("60.00"), Today.date().plusDays(3),
                collections.getId(), null, List.of()));
        actAs(northLogin);
        Dispute dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));

        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getRegion().getId()).isEqualTo(north.getId());
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getRegion().getId())
                .isEqualTo(north.getId());
        assertThat(promiseRepository.findById(promise.id()).orElseThrow().getRegion().getId())
                .isEqualTo(north.getId());
        assertThat(disputeRepository.findById(dispute.getId()).orElseThrow().getRegion().getId())
                .isEqualTo(north.getId());
    }

    @Test
    void aNewCustomerGoesInTheOnlyRegionTheCallerManagesCustomersIn() throws Exception {
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL);

        JsonNode created = read(mockMvc.perform(post("/api/customers").with(as(rita))
                        .contentType(MediaType.APPLICATION_JSON).content(json(newCustomer("South Co"))))
                .andExpect(status().isOk()));

        assertThat(regionOf(created.get("id").asLong())).isEqualTo(south.getId());
    }

    @Test
    void aCallerWhoManagesSeveralRegionsChoosesOneOrNarrowsToOne() throws Exception {
        mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(newCustomer("Nowhere Ltd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.regionId").value("Choose the region this customer belongs to"));
        assertThat(customerRepository.findAll()).isEmpty();

        JsonNode narrowed = read(mockMvc.perform(post("/api/customers").with(as(admin))
                        .param("regionIds", north.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(newCustomer("North Co"))))
                .andExpect(status().isOk()));
        JsonNode chosen = read(mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newCustomer("South Co", "regionId", south.getId()))))
                .andExpect(status().isOk()));

        assertThat(regionOf(narrowed.get("id").asLong())).isEqualTo(north.getId());
        assertThat(regionOf(chosen.get("id").asLong())).isEqualTo(south.getId());
    }

    @Test
    void aNewCustomerCannotGoWhereTheCallerMayNotPutIt() throws Exception {
        Region west = region("WEST");
        Region east = region("EAST");
        User rita = staff("rita", "CASHIER", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.MANAGE, RecordScope.ALL, east, AccessLevel.MANAGE, RecordScope.ALL);
        east.setActive(false);
        regionRepository.save(east);

        mockMvc.perform(post("/api/customers").with(as(rita)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(newCustomer("North Co", "regionId", north.getId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/customers").with(as(rita)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(newCustomer("West Co", "regionId", west.getId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/customers").with(as(rita)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(newCustomer("East Co", "regionId", east.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Region East is inactive"));
        assertThat(customerRepository.findAll()).as("nothing was written by a refusal").isEmpty();
        assertThat(userRepository.findByUsername("north.co")).isEmpty();

        JsonNode created = read(mockMvc.perform(post("/api/customers").with(as(rita))
                        .contentType(MediaType.APPLICATION_JSON).content(json(newCustomer("South Co"))))
                .andExpect(status().isOk()));
        assertThat(regionOf(created.get("id").asLong()))
                .as("an inactive region is not a choice, so SOUTH is the only one")
                .isEqualTo(south.getId());
    }

    private Payment payment(Customer customer, Invoice inv) {
        actAs(admin);
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(),
                new BigDecimal("40.00"), "Cash", null, List.of(inv.getId()), collections.getId(), null));
    }

    private PromiseDtos.PromiseDto promise(Customer customer) {
        actAs(admin);
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(customer.getId(),
                new BigDecimal("60.00"), Today.date().plusDays(3), collections.getId(), null, List.of()));
    }

    private Dispute dispute(Customer customer, Invoice inv) {
        actAs(customerUser(customer.getName().toLowerCase().replace(' ', '.') + ".login", customer.getId()));
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Wrong quantity", null));
        actAs(admin);
        return d;
    }

    /** A customer's records, one of each kind, so each scoped kind can be asked about by id and as a list. */
    private record Records(Payment payment, PromiseDtos.PromiseDto promise, Dispute dispute) {}

    private Records recordsOf(Customer customer) {
        Invoice inv = invoice(customer, sales);
        return new Records(payment(customer, inv), promise(customer), dispute(customer, inv));
    }

    @Test
    void paymentsPromisesAndDisputesAreScopedByRegionAsListsAndById() throws Exception {
        User rex = staff("rex", "ADMIN", north, AccessLevel.READ, RecordScope.ALL,
                south, AccessLevel.READ, RecordScope.ALL);
        pocsAlsoIn(north, south);
        Records home = recordsOf(customer("Default Co"));
        Records northCo = recordsOf(customer("North Co", north));
        Records southCo = recordsOf(customer("South Co", south));

        for (String list : List.of("/api/payments", "/api/promises", "/api/disputes")) {
            mockMvc.perform(get(list).with(as(rex)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.regionIds", contains(north.getId().intValue(), south.getId().intValue())));
            mockMvc.perform(get(list).with(as(rex)).param("regionIds", south.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].customerName").value("South Co"))
                    .andExpect(jsonPath("$.regionIds", contains(south.getId().intValue())));
        }

        mockMvc.perform(get("/api/payments/" + home.payment().getId()).with(as(rex)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/promises/" + home.promise().id()).with(as(rex)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/disputes/" + home.dispute().getId()).with(as(rex)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments/" + northCo.payment().getId()).with(as(rex))
                        .param("regionIds", south.getId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/promises/" + northCo.promise().id()).with(as(rex)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/disputes/" + southCo.dispute().getId()).with(as(rex)))
                .andExpect(status().isOk());
    }

    /**
     * Update, delete, cancel and the due-date preview have no own-customer check of their own; the
     * by-id check is what keeps a customer login to its own customer, whatever its role lets it manage.
     */
    @Test
    void aCustomerLoginThatMayManageStillReachesOnlyItsOwnCustomer() throws Exception {
        Set<Privilege> privileges = new HashSet<>(role("CUSTOMER").getPrivileges());
        Stream.of(Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_MANAGE, Privileges.PAYMENT_MANAGE)
                .forEach(n -> privileges.add(privilegeRepository.findByName(n).orElseThrow()));
        Role editor = roleRepository.findByName("CUSTOMER_EDITOR").orElseGet(() -> roleRepository.save(Role.builder()
                .name("CUSTOMER_EDITOR").description("Customer login that may edit").privileges(privileges).build()));
        Customer mine = customer("Mine Ltd");
        Customer other = customer("Other Ltd");
        Invoice myInvoice = invoice(mine, sales);
        Invoice otherInvoice = invoice(other, sales);
        Invoice otherPaid = invoice(other, sales);
        Payment otherPayment = payment(other, otherPaid);
        User login = customerUser("mine.login", mine.getId());
        login.setRole(editor);
        userRepository.save(login);

        mockMvc.perform(put("/api/customers/" + other.getId()).with(as(login))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Renamed"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/customers/" + other.getId()).with(as(login)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/invoices/" + otherInvoice.getId()).with(as(login))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("notes", "Mine now"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/invoices/" + otherInvoice.getId() + "/cancel").with(as(login)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(login))
                        .param("customerId", other.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/payments/" + otherPayment.getId()).with(as(login))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("notes", "Mine now"))))
                .andExpect(status().isNotFound());

        assertThat(customerRepository.findById(other.getId())).get()
                .extracting(Customer::getName).isEqualTo("Other Ltd");
        Invoice untouched = invoiceRepository.findById(otherInvoice.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(untouched.getNotes()).isNull();
        assertThat(paymentRepository.findById(otherPayment.getId()).orElseThrow().getNotes()).isNull();

        mockMvc.perform(get("/api/invoices/due-date-preview").with(as(login))
                        .param("customerId", mine.getId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/invoices/" + myInvoice.getId() + "/cancel").with(as(login)))
                .andExpect(status().isOk());
        assertThat(invoiceRepository.findById(myInvoice.getId()).orElseThrow().getStatus())
                .as("the role's privileges do reach the login's own customer").isEqualTo(InvoiceStatus.CANCELLED);
    }
}
