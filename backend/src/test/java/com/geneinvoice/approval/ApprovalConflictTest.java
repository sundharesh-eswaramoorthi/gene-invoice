package com.geneinvoice.approval;

import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionCustodyService;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens when the record moved while the change waited. Four layers, and this is the file
 * that proves each of them bites (B2):
 *
 * <ol>
 *   <li>the payload version — a change composed by an older build of the application;</li>
 *   <li>the row version — the record is not the record the change was composed against;</li>
 *   <li>the mutator's own guards, re-run live, which refuse with THEIR message and leave the
 *       change PENDING, because auto-rejecting on a transient conflict loses the maker's work;</li>
 *   <li>the target having gone or moved, which supersedes rather than leaves a change waiting on
 *       something nobody can decide about any more.</li>
 * </ol>
 *
 * <p>The distinction layers 2 and 3 draw is the money one: a StaleChangeException means "nobody
 * replays anything", and a BadRequestException from the mutator means "not today, try again".
 */
class ApprovalConflictTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired CustomerService customerService;
    @Autowired RegionCustodyService custodyService;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String THOUSAND = "1000.00";
    static final LocalDate NEXT_WEEK = LocalDate.now(ZoneOffset.UTC).plusDays(7);

    User admin;
    User maker;
    User checker;
    User collections;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        maker = user("mary.maker", makerRole().getName());
        checker = user("carl.checker", checkerRole().getName());
        collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = opened("Acme Ltd", "acme.login", defaultRegion());
        widget = product("Widget", "100.00");
    }

    // --------------------------------------------------------- layer 2: the row version

    @Test
    void anInvoiceEditedAfterTheChangeWasComposedMakesTheApprovalFailWithAConflictAndTheRecordUnchanged()
            throws Exception {
        Invoice big = invoiceFor(acme, 200);                 // 20,000.00
        threshold(defaultRegion().getId(), THOUSAND, true);

        Long changeId = heldCancel(big);

        // An edit that moves no money at all — InvoiceService.update is one of the seven
        // deliberately ungated writes — still moves the row version, and the row version is the
        // whole precondition: what was approved was THIS invoice as it was (B2, UI-09).
        actAs(admin);
        invoiceService.update(big.getId(), new InvoiceDtos.UpdateInvoiceRequest(
                "Renegotiated while the cancellation waited", null));

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This record changed while the change was waiting; reload it and raise the change again"));

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    @Test
    void aPaymentRecordedWhileAnItemChangeWasPendingDoesNotSilentlyRefundMoneyOnApproval()
            throws Exception {
        Invoice big = invoiceFor(acme, 100);                 // 10,000.00
        Invoice fresh = invoiceRepository.findById(big.getId()).orElseThrow();

        // Composed by hand: INVOICE_REPLACE_ITEMS is reachable today only through
        // DisputeService.applyChange, and B2-DISPUTE is the unit that gives it a maker-facing
        // path. What is under test is the precondition, not how the row was raised (B2).
        PendingChange trimToNothing = pendingChangeRepository.saveAndFlush(PendingChange.builder()
                .action(PendingAction.INVOICE_REPLACE_ITEMS)
                .targetType(PendingTargetType.INVOICE)
                .targetId(fresh.getId())
                .customerId(acme.getId())
                .regionId(defaultRegion().getId())
                .exposure(new BigDecimal("10000.00"))
                .thresholdApplied(new BigDecimal(THOUSAND))
                .payloadJson(json(new ApprovalDtos.ItemsChange(
                        List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00"))),
                        "one line instead of a hundred")))
                .summary("Replace the lines of invoice " + fresh.getInvoiceNumber())
                .targetVersion(fresh.getVersion())
                .status(PendingChangeStatus.PENDING)
                .requestedByUserId(maker.getId())
                .build());

        // The money that appeared while the change waited. replaceItems refunds paidAmount above
        // the new total straight into customer credit (InvoiceService), so replaying this against
        // a paid invoice would push 9,900.00 out of the book on nobody's say-so (B2).
        actAs(maker);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("10000.00"), "NEFT", null, List.of(fresh.getId()),
                collections.getId(), null));
        assertThat(creditOf(acme)).isEqualByComparingTo("0.00");

        mockMvc.perform(post("/api/approvals/" + trimToNothing.getId() + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        Invoice after = invoiceRepository.findById(big.getId()).orElseThrow();
        assertThat(after.getTotal()).isEqualByComparingTo("10000.00");
        assertThat(after.getPaidAmount()).isEqualByComparingTo("10000.00");
        // The hundred lines are still the hundred lines, read the way a person would read them.
        mockMvc.perform(get("/api/invoices/" + big.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(100))
                .andExpect(jsonPath("$.total").value(10000.00));
        assertThat(creditOf(acme)).isEqualByComparingTo("0.00");
        assertThat(pendingChangeRepository.findById(trimToNothing.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    // ------------------------------------------------ layer 3: the mutator's own guards

    @Test
    void anInvoiceCancelledAfterTheChangeWasComposedFailsWithTheMessageTheMutatorGivesAndTheChangeStaysPending()
            throws Exception {
        Invoice small = invoiceFor(acme, 5);                 // 500.00, under the limit below
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult held = mockMvc.perform(post("/api/promises").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                                new BigDecimal("50000.00"), NEXT_WEEK, collections.getId(),
                                "settlement", List.of(small.getId())))))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");

        // 500.00 is under the limit, so cancelling goes straight through and the promise's create
        // — which has no target and therefore no row version to check — sails past layer 2 and
        // meets the mutator itself (B2).
        mockMvc.perform(post("/api/invoices/" + small.getId() + "/cancel").with(as(maker)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Invoice " + small.getInvoiceNumber()
                                + " is cancelled and cannot be promised against"));

        assertThat(promiseRepository.count()).isZero();
        // Refused on live state, not judged: auto-rejecting on a transient conflict would lose
        // the maker's work, so the row waits for somebody to decide it deliberately (B2).
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    // ------------------------------------------------- layer 1: the payload version

    @Test
    void aChangeWhoseStoredPayloadVersionIsOlderThanTheApplicationsIsRefusedRatherThanApplied()
            throws Exception {
        Invoice big = invoiceFor(acme, 200);
        Invoice fresh = invoiceRepository.findById(big.getId()).orElseThrow();

        // Jackson does not complain about a field that no longer exists, so a payload written
        // under an older shape would be replayed with components silently defaulted — which for a
        // payment is a different amount landing on different invoices (B2).
        PendingChange lastYears = pendingChangeRepository.saveAndFlush(PendingChange.builder()
                .action(PendingAction.INVOICE_CANCEL)
                .targetType(PendingTargetType.INVOICE)
                .targetId(fresh.getId())
                .customerId(acme.getId())
                .regionId(defaultRegion().getId())
                .exposure(fresh.getBalance())
                .thresholdApplied(new BigDecimal(THOUSAND))
                .payloadJson("{}")
                .payloadVersion(PendingChange.PAYLOAD_VERSION - 1)
                .summary("Cancel invoice " + fresh.getInvoiceNumber())
                .targetVersion(fresh.getVersion())
                .status(PendingChangeStatus.PENDING)
                .requestedByUserId(maker.getId())
                .build());

        mockMvc.perform(post("/api/approvals/" + lastYears.getId() + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This change was raised by an older version"
                        + " of the application and cannot be applied; withdraw it and raise it again"));

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findById(lastYears.getId()).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    // ------------------------------------------- layer 4: the target went, or went elsewhere

    @Test
    void aPendingChangeOnADeletedCustomerIsSupersededNotLeftWaiting() throws Exception {
        Customer doomed = opened("Doomed Ltd", "doomed.login", defaultRegion());
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult money = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(doomed.getId(),
                                new BigDecimal("8000.00"), "NEFT", null, List.of(),
                                collections.getId(), null))))
                .andExpect(status().isAccepted())
                .andReturn();
        Long moneyChange = jsonLong(money, "pendingChangeId");

        MvcResult removal = mockMvc.perform(delete("/api/customers/" + doomed.getId())
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("CUSTOMER_DELETE"))
                .andReturn();
        Long removalChange = jsonLong(removal, "pendingChangeId");

        mockMvc.perform(post("/api/approvals/" + removalChange + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(customerRepository.findById(doomed.getId())).isEmpty();

        PendingChange stranded = pendingChangeRepository.findById(moneyChange).orElseThrow();
        assertThat(stranded.getStatus()).isEqualTo(PendingChangeStatus.SUPERSEDED);
        assertThat(stranded.getDecisionNotes()).isEqualTo("The account was deleted");
        // The blueprint's mandated rider: EVERY terminal transition sets decided_at, or B3's
        // as-of read reports a superseded row as outstanding for ever (B2, B3 INTEGRATION).
        assertThat(stranded.getDecidedAt()).isNotNull();
        assertThat(stranded.getDecidedByUserId()).isNull();      // nobody decided it

        // And the deletion's OWN change did not supersede itself on the way through
        // CustomerService.delete, which is the one thing ApprovalContext.applyingId is for (B2).
        PendingChange done = pendingChangeRepository.findById(removalChange).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PendingChangeStatus.APPROVED);
        assertThat(done.getDecidedByUserId()).isEqualTo(checker.getId());
        assertThat(done.getDecisionNotes()).isNotEqualTo("The account was deleted");

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> "CHANGE_SUPERSEDED".equals(n.getType()))
                .extracting(Notification::getUserId)
                .containsExactly(maker.getId());
    }

    @Test
    void deletingAnAccountTakesItsPlacementLedgerWithIt() throws Exception {
        Customer doomed = opened("Doomed Ltd", "doomed.login", defaultRegion());
        assertThat(customerRegionHistoryRepository.findOpen(doomed.getId()))
                .as("opened the way the application opens one, so there is a placement to lose")
                .isPresent();

        MvcResult removal = mockMvc.perform(delete("/api/customers/" + doomed.getId())
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andReturn();
        mockMvc.perform(post("/api/approvals/" + jsonLong(removal, "pendingChangeId") + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(customerRepository.findById(doomed.getId())).isEmpty();
        // customer_region_history.customer_id is a plain Long with no foreign key, so nothing in
        // the database does this: without the cascade the OPEN row survives, still saying in the
        // present tense that an account nobody can find is in that branch (B1, CP-04).
        assertThat(customerRegionHistoryRepository.findByCustomerIdOrderByValidFromAsc(doomed.getId()))
                .isEmpty();
        // And the accounts that did not go are untouched.
        assertThat(customerRegionHistoryRepository.findOpen(acme.getId())).isPresent();
    }

    @Test
    void aPendingChangeOnAnAccountThatMovesRegionIsSupersededAndNotReRegioned() throws Exception {
        Region west = region("WEST");
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult money = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                                new BigDecimal("8000.00"), "NEFT", null, List.of(),
                                collections.getId(), null))))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(money, "pendingChangeId");

        actAs(admin);
        custodyService.move(acme.getId(), west.getId(), null, "Reorganised into the west");

        PendingChange stranded = pendingChangeRepository.findById(changeId).orElseThrow();
        assertThat(stranded.getStatus()).isEqualTo(PendingChangeStatus.SUPERSEDED);
        assertThat(stranded.getDecidedAt()).isNotNull();
        assertThat(stranded.getDecisionNotes())
                .isEqualTo("The account moved to " + west.getName() + "; raise the change again");
        // Superseded and NOT re-stamped: region_id is written once at raise time and never
        // mutated, because B3 reads that column as the frozen raise-time region — and moving an
        // account into a friendlier branch must not walk a waiting change along with it
        // (B2, B3 INTEGRATION).
        assertThat(stranded.getRegionId()).isEqualTo(defaultRegion().getId());
        assertThat(stranded.getPendingKey()).isNull();
        assertThat(paymentRepository.count()).isZero();

        // And it cannot be approved afterwards, in either branch.
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------- the order the decision takes its locks in

    @Test
    void aDecisionLocksTheCustomersRowBeforeTheChangesOwn() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment(acme, "8000.00");

        List<String> sql = CountingStatements.capture(() ->
                mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isOk()));

        // ONE lock order, everywhere: the customer's row, then the change's. RegionCustodyService
        // .move takes the customer and then supersedes that account's waiting changes, and
        // CustomerService.delete does the same through PendingChangeCascade — so a decision that
        // took the pending_changes row first would close an ABBA cycle with either of them and
        // Postgres would break it by aborting one of the two at random (PPD-01, B2, B1).
        int customer = firstLock(sql, "customers");
        int change = firstLock(sql, "pending_changes");
        assertThat(customer).as("the decision locks the customer row at all").isNotNegative();
        assertThat(change).as("the decision locks the change's own row at all").isNotNegative();
        assertThat(change).as("customers is locked before pending_changes").isGreaterThan(customer);
        // The negative control, and the reason firstLock may be widened to two dialect spellings
        // without becoming a matcher that says yes to everything: the decision reads the region
        // name on the way out and takes no lock on it at all.
        assertThat(firstLock(sql, "regions"))
                .as("a plain read is not reported as a lock")
                .isNegative();
    }

    @Test
    void theRowVersionOfAPromiseIsReadUnderThatRowsOwnWriteLock() throws Exception {
        // Made before the limit exists, so the promise itself goes straight through and it is the
        // CANCEL that waits — a change with a target, and therefore with a row version.
        MvcResult made = mockMvc.perform(post("/api/promises").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                                new BigDecimal("50000.00"), NEXT_WEEK, collections.getId(),
                                "settlement", List.of()))))
                .andExpect(status().isOk())
                .andReturn();
        Long promiseId = jsonLong(made, "id");

        threshold(defaultRegion().getId(), THOUSAND, true);
        MvcResult held = mockMvc.perform(post("/api/promises/" + promiseId + "/cancel")
                        .with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");

        List<String> sql = CountingStatements.capture(() ->
                mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isOk()));

        // The customer lock does NOT serialise the writers of this row — PaymentService.update,
        // every PaymentPromiseService mutator and the overdue sweep take no customer lock at all
        // — so reading the version without the row's own lock is a check-then-act, and what the
        // approver would then read is the wrong 409 (PPD-01, B2).
        assertThat(firstLock(sql, "payment_promises"))
                .as("the promise's version is read for update, not merely read")
                .isNotNegative();
    }

    /**
     * Two spellings of one lock. H2 renders LockModeType.PESSIMISTIC_WRITE as {@code for update};
     * Postgres renders the same lock mode as {@code for no key update} when it arrives through an
     * HQL query — which is every findByIdForUpdate in this application — and as {@code for update}
     * when it arrives through EntityManager.find, which is how the payment and promise row
     * versions are read. FOR NO KEY UPDATE conflicts with FOR NO KEY UPDATE, FOR SHARE and FOR
     * UPDATE on the same row, so it serialises the two writers this lock order exists to keep
     * apart exactly as FOR UPDATE does; the only thing it admits alongside is the FOR KEY SHARE a
     * foreign key check takes, which is the point of the weaker mode.
     *
     * <p>Matching the H2 spelling alone, this helper reported NO customer lock at all on Postgres
     * — the one database where the ABBA cycle below can actually be closed and the one where a
     * deadlock detector would break it by aborting a decision at random (PPD-01, B2).
     */
    private static final Pattern ROW_WRITE_LOCK = Pattern.compile("\\bfor (no key )?update\\b");

    /** The position of the first statement that takes this table's row write lock, or -1. */
    private static int firstLock(List<String> sql, String table) {
        for (int i = 0; i < sql.size(); i++) {
            String statement = sql.get(i);
            // A select and not merely a statement naming the table: an ordinary
            // "update customers set ..." is a write, not the lock taken ahead of one.
            if (statement.startsWith("select") && statement.contains(table)
                    && ROW_WRITE_LOCK.matcher(statement).find()) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------ fixtures

    private Long heldPayment(Customer c, String amount) throws Exception {
        MvcResult held = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(c.getId(),
                                new BigDecimal(amount), "NEFT", null, List.of(),
                                collections.getId(), null))))
                .andExpect(status().isAccepted())
                .andReturn();
        return jsonLong(held, "pendingChangeId");
    }

    private Long heldCancel(Invoice inv) throws Exception {
        MvcResult held = mockMvc.perform(post("/api/invoices/" + inv.getId() + "/cancel")
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andReturn();
        return jsonLong(held, "pendingChangeId");
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    /** An account opened the way the application opens one, so it has the open placement row that
     *  RegionCustodyService.move needs (B1). */
    private Customer opened(String name, String username, Region where) {
        actAs(admin);
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, where.getId(), username, "Password1!"));
    }

    private Invoice invoiceFor(Customer c, int quantity) {
        actAs(admin);
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00")))));
        return invoiceRepository.findById(inv.getId()).orElseThrow();
    }

    private BigDecimal creditOf(Customer c) {
        return customerRepository.findById(c.getId()).orElseThrow().getCreditBalance();
    }

    private Role makerRole() {
        return roleWith("MONEY_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                Privileges.SCOPE_OVERRIDE, Privileges.APPROVAL_VIEW);
    }

    private Role checkerRole() {
        return roleWith("CHECKER_ONLY", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalConflictTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private Long jsonLong(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get(field).asLong();
    }
}
