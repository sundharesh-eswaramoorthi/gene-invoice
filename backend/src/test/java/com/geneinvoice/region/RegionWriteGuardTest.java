package com.geneinvoice.region;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDispatcher;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.mailservice.MailServiceDtos;
import com.geneinvoice.email.mailservice.MailServiceEventHandler;
import com.geneinvoice.email.transport.IncomingMailHandler;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.NotificationService;
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
import com.geneinvoice.promise.PromiseSweepScheduler;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The write side, which until this unit was completely unguarded. Every READ in the application
 * has been region-bounded since R4 by the query funnel itself, but a write goes nowhere near that
 * funnel: a caller who could see nothing outside their own branch could still bill an account in
 * another one, take money for it, promise on its behalf, reseat its POCs and delete it outright,
 * simply by naming its id. The three create paths had no scope check of any kind — not even the
 * POC book — so this closes a plain hole as well as a regional one (B1).
 *
 * <p>The two halves of the contract are asserted separately and deliberately. A record you merely
 * REACHED that your regions exclude answers 404, because an id space is being probed and the
 * record must read as one that does not exist (AUTH-08). A branch you can see but not MANAGE
 * answers 403 on a write, because nothing is being probed and the caller is owed the true reason
 * (D-46).
 */
class RegionWriteGuardTest extends IntegrationTestBase {

    @Autowired CustomerService customerService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired DisputeService disputeService;
    @Autowired PocService pocService;
    @Autowired PromiseSweepScheduler promiseSweepScheduler;
    @Autowired RegionAccess regionAccess;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired GmailConnectionService gmailConnections;
    @Autowired NotificationService notificationService;
    @Autowired TransactionTemplate transactions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MailTransport transport;

    private static final Instant RAISED = Instant.parse("2026-01-10T00:00:00Z");

    User admin;
    Region home;
    Region north;
    Customer homeAccount;
    Customer northAccount;
    Product widget;
    User sam;          // Sales POC, works in both branches
    User cora;         // Collection POC, works in both branches
    User nate;         // may MANAGE the north branch and has nothing at all in HQ
    User vera;         // may READ HQ and MANAGE the north branch
    Invoice homeInvoice;
    Invoice northInvoice;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        home = defaultRegion();
        north = region("NORTH");
        actAs(admin);
        widget = product("Widget", "100.00");
        homeAccount = opened("Home Ltd", "home.login", home);
        northAccount = opened("North Ltd", "north.login", north);

        sam = staffedIn("sam.sales", DataSeeder.ROLE_SALES_POC,
                Map.of(home.getId(), RegionRight.MANAGE, north.getId(), RegionRight.MANAGE));
        cora = staffedIn("cora.collections", DataSeeder.ROLE_COLLECTION_POC,
                Map.of(home.getId(), RegionRight.MANAGE, north.getId(), RegionRight.MANAGE));

        // A person whose role gives them every write privilege in the book, and whose GRANTS are
        // the only thing standing between them and another branch's records. Both of them hold
        // MANAGE somewhere, because a person holding VIEW and nothing else anywhere loses
        // INVOICE_MANAGE to the authority-drop rule (R3) and would never reach this unit's guards.
        nate = staffedIn("nate.north", writerRole().getName(), Map.of(north.getId(), RegionRight.MANAGE));
        vera = staffedIn("vera.visitor", writerRole().getName(),
                Map.of(home.getId(), RegionRight.VIEW, north.getId(), RegionRight.MANAGE));

        actAs(admin);
        homeInvoice = invoice(homeAccount);
        northInvoice = invoice(northAccount);
    }

    @Test
    void creatingAnInvoiceAgainstACustomerInARegionTheCallerCannotManageIsRefused() throws Exception {
        // Nate holds INVOICE_MANAGE and may exercise it in NORTH. Before this unit the customer id
        // in the body was the whole of the check, so this was a 200 and the HQ account was billed
        // by somebody who cannot see it, cannot collect it and will never be told about it.
        mockMvc.perform(post("/api/invoices").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createInvoice(homeAccount))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/invoices").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createInvoice(northAccount))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(northAccount.getId()));

        // The other two create paths were unguarded in exactly the same way.
        mockMvc.perform(post("/api/payments").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(homeAccount.getId(),
                                new BigDecimal("10.00"), "Cash", null, List.of(), cora.getId(), null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/promises").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PromiseDtos.CreatePromiseRequest(homeAccount.getId(),
                                new BigDecimal("10.00"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                                cora.getId(), null, List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aViewOnlyGrantCannotEditItsRegionsInvoicesButCanReadThem() throws Exception {
        // Vera may read HQ. That half is R4's and is unchanged: the record is hers to see.
        mockMvc.perform(get("/api/invoices/" + homeInvoice.getId()).with(as(vera)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(homeInvoice.getId()));

        // Changing it is a different question with a different answer, and the answer is 403 and
        // not 404: she has just been shown the record, so pretending it is missing would be a lie
        // she can disprove by refreshing the page (D-46).
        mockMvc.perform(patch("/api/invoices/" + homeInvoice.getId()).with(as(vera))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new InvoiceDtos.UpdateInvoiceRequest("Edited", null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/invoices/" + homeInvoice.getId() + "/cancel").with(as(vera)))
                .andExpect(status().isForbidden());

        // And the same person, the same privilege and the same endpoint in the branch she manages.
        mockMvc.perform(patch("/api/invoices/" + northInvoice.getId()).with(as(vera))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new InvoiceDtos.UpdateInvoiceRequest("Edited", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Edited"));

        assertThat(invoiceRepository.findById(homeInvoice.getId()).orElseThrow().getNotes()).isNull();
    }

    @Test
    void theMailWebhookDerivesTheRegionFromTheRecordAndNotFromACaller() {
        AtomicReference<RegionScope.SystemReason> reasonSeen = new AtomicReference<>();
        AtomicReference<Boolean> narrowed = new AtomicReference<>();
        IncomingMailHandler recording = (mail, hint) -> {
            reasonSeen.set(RegionScope.systemReason());
            narrowed.set(RegionScope.ambientRegions() != null);
            return Optional.empty();
        };
        // The real handler, built by hand because the webhook only exists under the mail-service
        // transport and the suite runs with none — a second Spring context would be a heavy price
        // for one bean (the MoneyOracleTest precedent of autowiring what the base does not expose).
        MailServiceEventHandler handler = new MailServiceEventHandler(
                emailRepository, emailRecipientRepository, recording, gmailConnections,
                notificationService, userRepository, transactions, objectMapper);

        // POST /api/mail-service/events is permitAll plus an HMAC: there is no SecurityContext at
        // all, so there is nobody whose grants could bound this work.
        SecurityContextHolder.clearContext();
        handler.apply(new MailServiceDtos.Event(1L, "message.received", Instant.now(),
                objectMapper.valueToTree(reply(admin))));

        assertThat(reasonSeen.get()).isEqualTo(RegionScope.SystemReason.MAIL_WEBHOOK);
        // asSystem and not asRegions: a reply belongs to whichever branch its own record belongs
        // to, and the webhook cannot know which before it has read it.
        assertThat(narrowed.get()).isFalse();

        // What that reason buys, stated as the gate sees it: inside the hatch a write in any branch
        // is allowed because the run is entitled to the whole company, and outside it there is
        // nobody to ask at all.
        RegionScope.asSystem(RegionScope.SystemReason.MAIL_WEBHOOK, () -> {
            regionAccess.requireManage(home.getId());
            regionAccess.requireManage(north.getId());
        });
        assertThatThrownBy(() -> regionAccess.requireManage(north.getId()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void everyOtherWriteOnAnAccountYouCanSeeButCannotManageIsRefused() {
        Payment homePayment = payment(homeAccount);
        PaymentPromise homePromise = promise(homeAccount);
        Long homeSeat = seat(homeAccount);
        Dispute homeDispute = dispute(homeInvoice, "home.login");

        actAs(vera);
        // The complete list of this unit's guards, in one place, so a guard quietly dropped later
        // is a failing assertion rather than a silent hole.
        refused(() -> invoiceService.create(createInvoice(homeAccount)));
        refused(() -> invoiceService.update(homeInvoice.getId(),
                new InvoiceDtos.UpdateInvoiceRequest("Edited", null)));
        refused(() -> invoiceService.cancel(homeInvoice.getId()));
        refused(() -> paymentService.record(new PaymentDtos.CreatePaymentRequest(homeAccount.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), cora.getId(), null)));
        refused(() -> paymentService.update(homePayment.getId(),
                new PaymentDtos.UpdatePaymentRequest("Edited", null)));
        refused(() -> promiseService.create(newPromise(homeAccount)));
        // The guard is INSIDE createAs and not on create(): the delegate self-invokes, so a check
        // on the wrapper is one every automated caller walks straight past (S4's warning).
        refused(() -> promiseService.createAs(vera.getId(), newPromise(homeAccount)));
        refused(() -> promiseService.update(homePromise.getId(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("25.00"), LocalDate.now(ZoneOffset.UTC).plusDays(3), null, null, null)));
        refused(() -> promiseService.cancel(homePromise.getId(), "no longer expected"));
        refused(() -> promiseService.override(homePromise.getId(), PromiseStatus.KEPT, "paid in cash"));
        refused(() -> promiseService.clearOverride(homePromise.getId()));
        refused(() -> customerService.update(homeAccount.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Renamed", null, null, null, null, null)));
        refused(() -> customerService.delete(homeAccount.getId()));
        refused(() -> pocService.add(homeAccount.getId(), PocType.COLLECTION, sam.getId(), false));
        refused(() -> pocService.remove(homeAccount.getId(), homeSeat));
        refused(() -> pocService.setPrimary(homeAccount.getId(), homeSeat));
        refused(() -> disputeService.approve(homeDispute.getId(), null));
        refused(() -> disputeService.deny(homeDispute.getId(), null));

        // Nothing of the account moved while all of that was being refused.
        assertThat(customerRepository.findById(homeAccount.getId())).isPresent();
        assertThat(promiseRepository.findById(homePromise.getId()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.OPEN);
        assertThat(customerPocRepository.findById(homeSeat)).isPresent();

        // The same person and the same privileges, in the branch she manages.
        actAs(vera);
        assertThat(paymentService.record(new PaymentDtos.CreatePaymentRequest(northAccount.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), cora.getId(), null)).getId())
                .isNotNull();
        assertThat(customerService.update(northAccount.getId(), new CustomerDtos.CustomerUpdateRequest(
                "North Renamed", null, null, null, null, null)).getName()).isEqualTo("North Renamed");
    }

    @Test
    void aRecordInABranchYouHaveNothingInIsNotFoundRatherThanForbidden() {
        Payment homePayment = payment(homeAccount);
        PaymentPromise homePromise = promise(homeAccount);

        actAs(nate);
        // Nate cannot see HQ at all, so the READ gate answers before the write gate does and the
        // answer is "no such record" — the refusal must not become a way of asking which ids
        // exist in a branch the caller has nothing in (AUTH-08).
        missing(() -> invoiceService.update(homeInvoice.getId(),
                new InvoiceDtos.UpdateInvoiceRequest("Edited", null)));
        missing(() -> invoiceService.cancel(homeInvoice.getId()));
        missing(() -> paymentService.update(homePayment.getId(),
                new PaymentDtos.UpdatePaymentRequest("Edited", null)));
        missing(() -> promiseService.cancel(homePromise.getId(), "not mine"));
        missing(() -> customerService.update(homeAccount.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Renamed", null, null, null, null, null)));
        missing(() -> customerService.delete(homeAccount.getId()));
    }

    @Test
    void aDisputeIsNowReadOneRecordAtATimeThroughTheSameFunnelItsListIs() throws Exception {
        Dispute homeDispute = dispute(homeInvoice, "home.login");

        // Disputes are the one list where staff hold no book at all, so before this unit any member
        // of staff could read any dispute in the company by id. A visible behaviour change, and the
        // release note's.
        mockMvc.perform(get("/api/disputes/" + homeDispute.getId()).with(as(nate)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/disputes/" + homeDispute.getId()).with(as(vera)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(homeDispute.getId()));

        // Deciding it is a write, so the branch Vera can only read refuses her with 403 while the
        // branch Nate cannot see at all keeps answering 404.
        mockMvc.perform(post("/api/disputes/" + homeDispute.getId() + "/approve").with(as(nate)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/disputes/" + homeDispute.getId() + "/approve").with(as(vera)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvingADisputeStillAppliesTheChangeThroughTheRenamedEscapeHatches() {
        Dispute homeDispute = dispute(homeInvoice, "home.login");

        // The three hatches into InvoiceService read past the book and past the region predicate by
        // design — the customer who raised this holds no grants at all — and the check that stands
        // for all three is on the APPROVAL, which admin passes.
        actAs(admin);
        disputeService.approve(homeDispute.getId(), null);

        assertThat(invoiceRepository.findById(homeInvoice.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void aPersonYouShareNoBranchWithIsNotFoundRatherThanForbidden() throws Exception {
        User hilda = staffedIn("hilda.hq", writerRole().getName(), Map.of(home.getId(), RegionRight.MANAGE));

        mockMvc.perform(get("/api/users/" + hilda.getId()).with(as(nate)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/" + hilda.getId() + "/gmail").with(as(nate)))
                .andExpect(status().isNotFound());
        // The audit timeline asked about the same person, through the same gate: its default arm
        // used to wave USER straight through.
        mockMvc.perform(get("/api/audit?entityType=USER&entityId=" + hilda.getId()).with(as(nate)))
                .andExpect(status().isNotFound());

        // A person is visible where they work, so Nate can still read himself, and the products
        // catalogue stays company-wide because it is unregioned by declaration rather than by
        // omission.
        mockMvc.perform(get("/api/users/" + nate.getId()).with(as(nate)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit?entityType=PRODUCT&entityId=" + widget.getId()).with(as(nate)))
                .andExpect(status().isOk());
    }

    @Test
    void recomputingPromisesTouchesOnlyTheAccountsTheCallerCanSee() {
        PaymentPromise homePromise = overduePromise(homeAccount);
        PaymentPromise northPromise = overduePromise(northAccount);

        // The sweep behind POST /api/promises/recompute reads every customer with a live promise,
        // which knows nothing about who is asking; its id list is now intersected through the same
        // funnel every list goes through.
        actAs(nate);
        List<PaymentPromiseService.RecomputeChange> changes = promiseService.recomputeAll(true);

        assertThat(changes).extracting(PaymentPromiseService.RecomputeChange::customerId)
                .containsExactly(northAccount.getId());
        assertThat(promiseRepository.findById(northPromise.getId()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.BROKEN);
        assertThat(promiseRepository.findById(homePromise.getId()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.OPEN);

        // And somebody who works everywhere still repairs everything.
        actAs(admin);
        assertThat(promiseService.recomputeAll(true))
                .extracting(PaymentPromiseService.RecomputeChange::customerId)
                .containsExactlyInAnyOrder(homeAccount.getId());
    }

    @Test
    void theSweeperRunsWithNobodySignedInAndStillReachesEveryBranch() {
        PaymentPromise homePromise = overduePromise(homeAccount);
        PaymentPromise northPromise = overduePromise(northAccount);

        // The scheduler's own thread has no principal, and "nobody" reads as no regions at all
        // rather than as every region, which is the safe direction and the wrong answer for a
        // sweep. The job method is called directly, never waited for (test conventions).
        SecurityContextHolder.clearContext();
        promiseSweepScheduler.sweep();

        assertThat(promiseRepository.findById(homePromise.getId()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.BROKEN);
        assertThat(promiseRepository.findById(northPromise.getId()).orElseThrow().getStatus())
                .isEqualTo(PromiseStatus.BROKEN);
    }

    @Test
    void theEmailDispatchersOwnThreadNamesWhyItMayReachEveryBranch() throws Exception {
        AtomicReference<RegionScope.SystemReason> reasonSeen = new AtomicReference<>();
        CountDownLatch reached = new CountDownLatch(1);
        // The first thing the worker does with the email is open a transaction, so this is where
        // the daemon thread can be asked what it thinks it is entitled to.
        TransactionTemplate recording = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                reasonSeen.set(RegionScope.systemReason());
                reached.countDown();
                return super.execute(action);
            }
        };
        EmailDispatcher dispatcher = new EmailDispatcher(emailRepository, emailRecipientRepository,
                transport, recording, true);

        SecurityContextHolder.clearContext();
        // A nonexistent id: the claim finds nothing and the worker stops, which is all this needs —
        // what is being asserted is the reason the THREAD carries, not what it delivers.
        dispatcher.dispatchAll(List.of(-1L));

        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
        // The wrap is on the worker's body and not on dispatchAll: the hatch is a ThreadLocal and
        // is deliberately not inheritable, so wrapping the submit call would leave this thread with
        // nothing at all (B1).
        assertThat(reasonSeen.get()).isEqualTo(RegionScope.SystemReason.EMAIL_DISPATCH);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** An account opened the way the application opens one, so it has its opening placement. */
    private Customer opened(String name, String username, Region where) {
        return customerService.create(new CustomerDtos.CustomerCreateRequest(
                name, null, null, null, null, where.getId(), username, "Password1!"));
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
     * Every write privilege this unit guards, plus SCOPE_OVERRIDE so the POC book contributes no
     * predicate at all: the book axis and the region axis are ANDed, and only a role like this can
     * tell the two apart. Roles are not cleared between tests, so it is find-or-create.
     */
    private Role writerRole() {
        return roleRepository.findByName("REGION_WRITER").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("REGION_WRITER")
                        .description("Writes everything, in the branches they are staffed in")
                        .privileges(Stream.of(Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                                        Privileges.PRODUCT_VIEW,
                                        Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                                        Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                                        Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                                        Privileges.PROMISE_OVERRIDE,
                                        Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                                        Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                                        Privileges.USER_VIEW, Privileges.AUDIT_VIEW,
                                        Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
    }

    private InvoiceDtos.CreateInvoiceRequest createInvoice(Customer c) {
        return new InvoiceDtos.CreateInvoiceRequest(c.getId(), RAISED, null, null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00"))));
    }

    private Invoice invoice(Customer c) {
        return invoiceService.create(createInvoice(c));
    }

    private Payment payment(Customer c) {
        actAs(admin);
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(c.getId(),
                new BigDecimal("10.00"), "Cash", null, List.of(), cora.getId(), null));
    }

    private PromiseDtos.CreatePromiseRequest newPromise(Customer c) {
        return new PromiseDtos.CreatePromiseRequest(c.getId(), new BigDecimal("50.00"),
                LocalDate.now(ZoneOffset.UTC).plusDays(7), cora.getId(), null, List.of());
    }

    private PaymentPromise promise(Customer c) {
        actAs(admin);
        return promiseRepository.findById(promiseService.create(newPromise(c)).id()).orElseThrow();
    }

    /**
     * A promise whose date has gone by while its status still says OPEN — the shape both the
     * recompute and the sweeper exist to repair. The date is moved on the row rather than promised
     * in the past, because creating one evaluates it on the spot.
     */
    private PaymentPromise overduePromise(Customer c) {
        PaymentPromise p = promise(c);
        p.setPromisedDate(LocalDate.now(ZoneOffset.UTC).minusDays(3));
        return promiseRepository.save(p);
    }

    /** A seat on the account, and its id, so the two seat writes have something to aim at. */
    private Long seat(Customer c) {
        actAs(admin);
        return pocService.add(c.getId(), PocType.COLLECTION, cora.getId(), true).getId();
    }

    /** A dispute raised by the account's own login, proposing that the invoice be cancelled. */
    private Dispute dispute(Invoice inv, String login) {
        actAs(userRepository.findByUsername(login).orElseThrow());
        Dispute d = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.INVOICE, inv.getId(), "Billed in error", "{\"action\":\"cancel\"}"));
        actAs(admin);
        return d;
    }

    private MailServiceDtos.MessageReceived reply(User owner) {
        return new MailServiceDtos.MessageReceived(String.valueOf(owner.getId()),
                owner.getEmail(), null, "provider-1", "thread-1", "<rfc-1@test.local>",
                null, List.of(), new MailServiceDtos.Party("Home Ltd", "home@test.local"),
                List.of(new MailServiceDtos.Party("Owner", owner.getEmail())), List.of(),
                "Re: invoice", "Thanks", Instant.now());
    }

    private void refused(Runnable write) {
        assertThatThrownBy(write::run).isInstanceOf(AccessDeniedException.class);
    }

    private void missing(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(com.geneinvoice.common.NotFoundException.class);
    }
}
