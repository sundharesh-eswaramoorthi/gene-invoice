package com.geneinvoice.region;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moving an account between branches. The load-bearing claim of the whole anchored model is tested
 * here: because region lives on the customer and nowhere else, a move is one row lock, one closed
 * interval and one opened one — with no child to stamp, no cascade, and no window in which an
 * invoice disagrees with the account it belongs to (B1).
 */
class CustomerRegionMoveTest extends IntegrationTestBase {

    static final Instant RAISED = Instant.parse("2026-03-01T09:00:00Z");

    @Autowired CustomerService customerService;
    @Autowired RegionCustodyService custodyService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PocService pocService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired AuditService auditService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    Region home;
    Region north;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        home = defaultRegion();
        north = region("NORTH");
        actAs(admin);
        widget = product("Widget", "100.00");
    }

    @Test
    void movingAnAccountMovesItsInvoicesPaymentsPromisesAndDisputesInTheSameBreath() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        User sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);

        Invoice invoice = invoice(acme, sales);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), collections.getId(), null));
        promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("50.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                collections.getId(), null, List.of()));
        disputeRepository.save(Dispute.builder()
                .customerId(acme.getId()).openedByUserId(admin.getId())
                .targetType(DisputeTargetType.INVOICE).targetId(invoice.getId())
                .reason("raised before the move").build());

        User here = readerIn("hank.home", home);
        User there = readerIn("nell.north", north);

        // Before: the account and all four of its records belong to the branch that holds it, and
        // the branch it is about to join can see none of it.
        assertSees(here, 1);
        assertSees(there, 0);

        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionCustodyService.MoveRegionRequest(
                                north.getId(), null, "Account reorganised into the north"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.fromRegionId").value(home.getId()))
                .andExpect(jsonPath("$.fromRegionCode").value(home.getCode()))
                .andExpect(jsonPath("$.toRegionId").value(north.getId()))
                .andExpect(jsonPath("$.toRegionCode").value("NORTH"))
                .andExpect(jsonPath("$.effectiveFrom").value(InvoiceDates.todayForWrite().toString()))
                // The collections POC works in the branch the account has just left, so her seat
                // goes with the move: a seat that grants nothing is a seat that misleads, and it is
                // given up through the ordinary removal so the next primary is promoted (B1, D-44).
                .andExpect(jsonPath("$.pocSeatsVacated").value(1));

        // After: all four moved with it, in one transaction, with not a single row of their own
        // rewritten — they have no region to rewrite. That is the whole point of anchoring the
        // axis on the customer (B1).
        assertSees(here, 0);
        assertSees(there, 1);
    }

    @Test
    void aMoveClosesTheOpenHistoryRowAndOpensExactlyOneMore() {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        assertThat(placements(acme))
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.getRegionId()).isEqualTo(home.getId());
                    assertThat(h.getValidFrom()).isEqualTo(InvoiceDates.todayForWrite());
                    assertThat(h.getValidTo()).isNull();
                });

        LocalDate when = InvoiceDates.todayForWrite().plusDays(1);
        custodyService.move(acme.getId(), north.getId(), when, "scheduled for tomorrow");

        List<CustomerRegionHistory> after = placements(acme);
        assertThat(after).hasSize(2);
        // Half-open [validFrom, validTo): the closed row ends exactly where the open one begins, so
        // every date resolves to exactly one placement and none resolves to two.
        assertThat(after.get(0).getValidTo()).isEqualTo(when);
        assertThat(after.get(1).getValidFrom()).isEqualTo(when);
        assertThat(after.get(1).getValidTo()).isNull();
        assertThat(after.get(1).getMovedByUserId()).isEqualTo(admin.getId());
        assertThat(after.get(1).getReason()).isEqualTo("scheduled for tomorrow");
    }

    /**
     * The ORDER of the two writes, not just their outcome. Hibernate's action queue runs every
     * INSERT ahead of every UPDATE, so without the flush in RegionCustodyService.move the row that
     * opens the destination is written while the row it replaces is still open — and on Postgres
     * uk_crh_open, the partial unique index that makes "exactly one open placement" a database
     * invariant, rejects the whole move with a 409. H2 has no partial index, so the outcome here
     * is identical either way and only the statement order can tell the two apart. Verified
     * against a real Postgres: before the flush, every move answered 409 with "duplicate key value
     * violates unique constraint uk_crh_open" (B1).
     */
    @Test
    void theOpenPlacementIsClosedInTheDatabaseBeforeItsReplacementIsInserted() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);

        List<String> sql = CountingStatements.capture(
                () -> custodyService.move(acme.getId(), north.getId(), null, "ordering"));

        List<String> writes = sql.stream()
                .filter(s -> s.contains("customer_region_history"))
                .filter(s -> s.startsWith("update") || s.startsWith("insert"))
                .map(s -> s.substring(0, 6))
                .toList();
        assertThat(writes)
                .as("the close is sent first, so the index never sees two open rows at once")
                .containsExactly("update", "insert");
    }

    @Test
    void aCustomerHasExactlyOneOpenHistoryRowAfterTwoMoves() {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        Region west = region("WEST");

        custodyService.move(acme.getId(), north.getId(), null, "first");
        custodyService.move(acme.getId(), west.getId(), null, "second");

        assertThat(placements(acme)).hasSize(3);
        assertThat(open(acme)).singleElement()
                .satisfies(h -> assertThat(h.getRegionId()).isEqualTo(west.getId()));
        assertThat(regionOf(acme)).isEqualTo(west.getId());

        // A move to the branch an account is already in is not a move: nothing is closed, nothing
        // is opened and the ledger does not grow a row saying nothing happened.
        RegionCustodyService.MoveResult nothing =
                custodyService.move(acme.getId(), west.getId(), null, "already there");
        assertThat(nothing.pocSeatsVacated()).isZero();
        assertThat(placements(acme)).hasSize(3);
    }

    @Test
    void twoSimultaneousMovesLeaveExactlyOneOpenHistoryRow() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        Region west = region("WEST");

        List<Object> outcomes = inParallel(
                () -> custodyService.move(acme.getId(), north.getId(), null, "north wants it"),
                () -> custodyService.move(acme.getId(), west.getId(), null, "so does west"));

        // The customer row lock is taken FIRST, so the two moves QUEUE rather than both reading the
        // same open placement and both closing it. Both therefore succeed, and — the part that
        // proves they serialised rather than merely both finishing — the second one read the
        // FIRST one's placement as the branch it was moving out of. Remove the lock and this is a
        // failed optimistic lock on the customer instead: right invariant, wrong reason, and one
        // of the two moves lost (B1).
        assertThat(outcomes).allSatisfy(o -> assertThat(o)
                .isInstanceOf(RegionCustodyService.MoveResult.class));
        List<RegionCustodyService.MoveResult> moves = outcomes.stream()
                .map(RegionCustodyService.MoveResult.class::cast).toList();
        RegionCustodyService.MoveResult a = moves.get(0);
        RegionCustodyService.MoveResult b = moves.get(1);
        RegionCustodyService.MoveResult first = home.getId().equals(a.fromRegionId()) ? a : b;
        RegionCustodyService.MoveResult second = first == a ? b : a;
        assertThat(first.fromRegionId())
                .as("one of them started from the branch the account was actually in")
                .isEqualTo(home.getId());
        assertThat(second.fromRegionId())
                .as("and the other began where the first one ended, rather than where it began")
                .isEqualTo(first.toRegionId());

        // On H2 the row lock is the ONLY thing holding "exactly one open placement" — uk_crh_open
        // is a Postgres-only partial index — so this is the one place that invariant is proven.
        assertThat(placements(acme)).hasSize(3);
        assertThat(open(acme)).hasSize(1);
        assertThat(regionOf(acme))
                .as("the column and the ledger agree whichever move went last")
                .isEqualTo(open(acme).get(0).getRegionId());
    }

    @Test
    void anInvoiceRaisedWhileAMoveIsRunningAgreesWithItsCustomerEitherWay() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        Region west = region("WEST");
        // The account is raised against in all three branches over the life of this test, and from
        // R8 a Sales POC is refused in a branch they cannot MANAGE, so sam works in all three. He
        // is never a caller here — every read below is the administrator's (B1, R8).
        User sales = staffedIn("sam.sales", DataSeeder.ROLE_SALES_POC,
                Map.of(home.getId(), RegionRight.MANAGE,
                        north.getId(), RegionRight.MANAGE,
                        west.getId(), RegionRight.MANAGE));

        Invoice before = invoice(acme, sales);
        custodyService.move(acme.getId(), north.getId(), null, "reorganised");
        // An invoice raised before the move is in the new branch the moment the account is: it
        // never carried a region of its own to be left behind.
        assertThat(reportedRegionOf(before)).isEqualTo(north.getId());
        assertThat(reportedRegionOf(invoice(acme, sales))).isEqualTo(north.getId());

        // And in the same instant. There is no cascade to race: whichever side of the commit the
        // insert lands on, the invoice reads its region THROUGH its customer at query time, so it
        // agrees with the account rather than with a copy of where the account used to be. This is
        // why InvoiceService.create needs no row lock on the customer for B1.
        List<Object> outcomes = inParallel(
                () -> custodyService.move(acme.getId(), west.getId(), null, "moving again"),
                () -> invoice(acme, sales));

        Long settled = regionOf(acme);
        assertThat(settled).isIn(north.getId(), west.getId());
        for (Object o : outcomes) {
            if (o instanceof Invoice raised) {
                assertThat(reportedRegionOf(raised))
                        .as("an invoice raised during a move agrees with its customer")
                        .isEqualTo(settled);
            }
        }
        assertThat(reportedRegionOf(before)).isEqualTo(settled);
    }

    @Test
    void aUserWhoCannotManageTheDestinationCannotMoveACustomerThere() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);

        // Manages the branch the account is in, and nothing in the one it would be moved to.
        User hal = staffedIn("hal.home", "CASHIER", Map.of(home.getId(), RegionRight.MANAGE));
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region").with(as(hal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionCustodyService.MoveRegionRequest(
                                north.getId(), null, null))))
                // 403 and not 404: he NAMED the destination, so there is no id space to protect and
                // pretending the branch does not exist would be a lie he can disprove (D-46).
                .andExpect(status().isForbidden());

        // And the other half of "MANAGE in BOTH": he may read the branch the account sits in but
        // may not change anything there, so he cannot move it out either.
        User nora = staffedIn("nora.north", "CASHIER",
                Map.of(home.getId(), RegionRight.VIEW, north.getId(), RegionRight.MANAGE));
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region").with(as(nora))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionCustodyService.MoveRegionRequest(
                                north.getId(), null, null))))
                .andExpect(status().isForbidden());

        // A caller who cannot even SEE the account meets a 404 instead, which is the other rule and
        // is never relaxed (AUTH-08).
        User stranger = staffedIn("stan.stranger", "CASHIER", Map.of(north.getId(), RegionRight.MANAGE));
        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region").with(as(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionCustodyService.MoveRegionRequest(
                                north.getId(), null, null))))
                .andExpect(status().isNotFound());

        assertThat(regionOf(acme)).isEqualTo(home.getId());
        assertThat(placements(acme)).hasSize(1);
    }

    @Test
    void aMoveCannotBeBackdatedBeforeTheAccountArrived() throws Exception {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        LocalDate arrived = InvoiceDates.todayForWrite();

        mockMvc.perform(post("/api/customers/" + acme.getId() + "/region").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegionCustodyService.MoveRegionRequest(
                                north.getId(), arrived.minusDays(1), "yesterday"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "A move cannot be dated before the account arrived here on " + arrived));

        // Nothing half-happened: the refusal is before the first write, so the ledger still holds
        // exactly the opening placement.
        assertThat(placements(acme)).hasSize(1);
        assertThat(regionOf(acme)).isEqualTo(home.getId());

        // The same date the account arrived on IS allowed — [validFrom, validTo) is half-open, so a
        // same-day move leaves an interval nothing can resolve to rather than an overlap.
        actAs(admin);
        custodyService.move(acme.getId(), north.getId(), arrived, "later the same day");
        assertThat(open(acme)).singleElement()
                .satisfies(h -> assertThat(h.getValidFrom()).isEqualTo(arrived));
    }

    @Test
    void aMoveRecordsOneAuditEntryNamingBothRegions() {
        Customer acme = opened("Acme Ltd", "acme.login", home);
        custodyService.move(acme.getId(), north.getId(), null, "Account reorganised");

        List<AuditLog> moves = auditService.historyFor(CustomerService.ENTITY, acme.getId()).stream()
                .filter(a -> RegionCustodyService.ACTION_REGION_CHANGED.equals(a.getAction()))
                .toList();

        // ONE row on the customer, not one blob per moved invoice: customer_region_history is the
        // per-record history for this dimension and this is the entry that points at it (B1).
        assertThat(moves).hasSize(1);
        AuditLog moved = moves.get(0);
        assertThat(moved.getBeforeJson()).contains(home.getCode()).contains(String.valueOf(home.getId()));
        assertThat(moved.getAfterJson()).contains("NORTH").contains(String.valueOf(north.getId()));
        assertThat(moved.getChangedByUserId()).isEqualTo(admin.getId());
        assertThat(moved.getReason())
                .contains(home.getCode()).contains("NORTH").contains("Account reorganised");
    }

    @Test
    void creatingACustomerWithoutARegionIsRefusedWhenTheCallerHoldsSeveral() throws Exception {
        User cara = staffedIn("cara.cashier", "CASHIER",
                Map.of(home.getId(), RegionRight.MANAGE, north.getId(), RegionRight.MANAGE));

        mockMvc.perform(post("/api/customers").with(as(cara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Nova Ltd", null, null,
                                null, null, null, "nova.login", "Password1!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.regionId").value("Choose a region"));

        // The administrator's wildcard is not an answer either, once there is more than one branch
        // to choose between: "everywhere" is not a place to file an account.
        mockMvc.perform(post("/api/customers").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Orbit Ltd", null, null,
                                null, null, null, "orbit.login", "Password1!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.regionId").value("Choose a region"));

        assertThat(customerRepository.findAll()).isEmpty();

        // Naming one she manages works, and the row says where it went.
        mockMvc.perform(post("/api/customers").with(as(cara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Nova Ltd", null, null,
                                null, null, north.getId(), "nova.login", "Password1!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(north.getId()))
                .andExpect(jsonPath("$.regionName").value(north.getName()));

        // Naming one she does not is 403, because she named it (D-46).
        Region west = region("WEST");
        mockMvc.perform(post("/api/customers").with(as(cara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Far Ltd", null, null,
                                null, null, west.getId(), "far.login", "Password1!"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingACustomerWithoutARegionDefaultsToTheCallersOnlyRegion() throws Exception {
        User nate = staffedIn("nate.north", "CASHIER", Map.of(north.getId(), RegionRight.MANAGE));

        mockMvc.perform(post("/api/customers").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerDtos.CustomerCreateRequest("Nova Ltd", null, null,
                                null, null, null, "nova.login", "Password1!"))))
                .andExpect(status().isOk())
                // Not the company's default branch: the one branch HE works in. An account opened
                // by the north office belongs to the north office.
                .andExpect(jsonPath("$.regionId").value(north.getId()));

        Customer nova = customerRepository.findAll().stream()
                .filter(c -> c.getName().equals("Nova Ltd")).findFirst().orElseThrow();
        assertThat(regionOf(nova)).isEqualTo(north.getId());
        // And it is opened in the ledger as well as in the column, dated today, in the same
        // transaction — so an as-of read of a brand-new account resolves.
        assertThat(placements(nova)).singleElement().satisfies(h -> {
            assertThat(h.getRegionId()).isEqualTo(north.getId());
            assertThat(h.getValidFrom()).isEqualTo(InvoiceDates.todayForWrite());
            assertThat(h.getValidTo()).isNull();
            assertThat(h.getMovedByUserId()).isEqualTo(nate.getId());
        });
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** An account opened the way the application opens one, so it has its opening placement. */
    private Customer opened(String name, String username, Region where) {
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, where.getId(), username, "Password1!"));
    }

    private Invoice invoice(Customer c, User sales) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), RAISED, null,
                null, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));
    }

    private List<CustomerRegionHistory> placements(Customer c) {
        return customerRegionHistoryRepository.findByCustomerIdOrderByValidFromAsc(c.getId());
    }

    private List<CustomerRegionHistory> open(Customer c) {
        return placements(c).stream().filter(h -> h.getValidTo() == null).toList();
    }

    /** The live column, read back from the database rather than from the instance we wrote. */
    private Long regionOf(Customer c) {
        return customerRepository.findById(c.getId()).orElseThrow().getRegion().getId();
    }

    /** What the invoice TELLS a reader its region is, which is the thing that must agree. */
    private Long reportedRegionOf(Invoice invoice) throws Exception {
        String body = mockMvc.perform(get("/api/invoices/" + invoice.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The filter chain clears the SecurityContext it set up, so the test thread's own
        // principal is restored for whatever this test calls a service with next.
        actAs(admin);
        return objectMapper.readTree(body).get("regionId").asLong();
    }

    private void assertSees(User reader, int expected) throws Exception {
        for (String path : List.of("/api/customers", "/api/invoices", "/api/payments",
                "/api/promises", "/api/disputes")) {
            mockMvc.perform(get(path).with(as(reader)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(expected));
        }
    }

    /** Somebody who may read every regional list, in exactly one branch. */
    private User readerIn(String username, Region where) {
        Role role = roleRepository.findByName("MOVE_READER").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("MOVE_READER")
                        .description("Reads every regional list in one region")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW,
                                        Privileges.PAYMENT_VIEW, Privileges.PROMISE_VIEW,
                                        Privileges.DISPUTE_VIEW, Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return staffedIn(username, role.getName(), Map.of(where.getId(), RegionRight.VIEW));
    }

    /** Somebody staffed in exactly the branches named, at exactly the levels named, and nowhere else. */
    private User staffedIn(String username, String roleName, Map<Long, RegionRight> where) {
        User u = user(username, roleName);
        revokeRegionGrants(u);
        where.forEach((regionId, right) -> userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build()));
        return u;
    }

    /**
     * Two pieces of work on two threads, started together. Each carries its own principal, because
     * the SecurityContext is a ThreadLocal; a task that throws is returned as its exception rather
     * than failing the test, because a lost row lock is a legitimate outcome and the invariant is
     * what the caller asserts on.
     */
    private List<Object> inParallel(Work first, Work second) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Work work : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    actAs(admin);
                    go.await(10, TimeUnit.SECONDS);
                    return work.run();
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    outcomes.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Work {
        Object run();
    }
}
