package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decision half, end to end: who may decide, who may never decide, and what the replay does
 * when somebody says yes (B2).
 *
 * <p>The test that must not be skipped is the asMaker pair — {@code
 * approvingAChangeOnARecordOutsideTheApproversBookStillAppliesIt} and {@code
 * theAuditRowsTheMutatorWritesOnApprovalNameTheMaker}. Replay under the approver's own principal
 * would either be refused by the maker's POC book or land the maker's money under the approver's
 * name, and neither failure looks like a failure from outside.
 *
 * <p>Every 403 here is asserted on the status and not on the sentence, because
 * GlobalExceptionHandler answers every AccessDeniedException with one fixed message and has done
 * since long before B2. The sentences are asserted where they survive to the wire: the 409s.
 */
class ApprovalDecisionTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired AuditService auditService;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String THOUSAND = "1000.00";

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
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
    }

    // ------------------------------------------------------------------- the replay itself

    @Test
    void approvingThePendingPaymentRecordsItAndTheMoneyLandsExactlyAsAnUnheldPaymentWould() throws Exception {
        // The control runs first, while nothing is held: one invoice of 5,000.00 and a payment of
        // 8,000.00, which pays it off and leaves 3,000.00 in credit.
        Customer control = customer("Control Ltd");
        Invoice controlInvoice = invoiceFor(control, 50);
        Invoice heldInvoice = invoiceFor(acme, 50);
        actAs(maker);
        Payment straightThrough = paymentService.record(
                payment(control, "8000.00", List.of(controlInvoice.getId())));

        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult held = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(acme, "8000.00", List.of(heldInvoice.getId())))))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(paymentRepository.count()).isEqualTo(1);      // the control's, and nothing else

        Long changeId = jsonLong(held, "pendingChangeId");
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("APPROVED"))
                .andExpect(jsonPath("$.change.decidedByUserId").value(checker.getId()))
                .andExpect(jsonPath("$.result.amount").value(8000.00));

        Payment approved = paymentRepository.findAll().stream()
                .filter(p -> p.getCustomer().getId().equals(acme.getId()))
                .findFirst().orElseThrow();
        assertThat(approved.getAmount()).isEqualByComparingTo(straightThrough.getAmount());
        assertThat(approved.getCreditApplied()).isEqualByComparingTo(straightThrough.getCreditApplied());

        Invoice heldAfter = invoiceRepository.findById(heldInvoice.getId()).orElseThrow();
        Invoice controlAfter = invoiceRepository.findById(controlInvoice.getId()).orElseThrow();
        assertThat(heldAfter.getPaidAmount()).isEqualByComparingTo(controlAfter.getPaidAmount());
        assertThat(heldAfter.getStatus()).isEqualTo(controlAfter.getStatus());
        assertThat(heldAfter.getStatus()).isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(creditOf(acme)).isEqualByComparingTo(creditOf(control));
        assertThat(creditOf(acme)).isEqualByComparingTo("3000.00");
    }

    @Test
    void approvingAChangeAppliesItWithoutAskingForASecondApproval() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // The replay walks straight back through the gate that held it. Without
        // ApprovalContext.applying the applier would hold its own work for approval for ever, and
        // the shape of that bug is a second PENDING row rather than a visible failure (B2).
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.APPROVED);
        assertThat(pendingChangeRepository.findByPendingKey("PAYMENT:" + changeId)).isEmpty();
    }

    @Test
    void approvingAChangeOnARecordOutsideTheApproversBookStillAppliesIt() throws Exception {
        Invoice big = invoiceFor(acme, 200);                     // 20,000.00
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult held = mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel")
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andReturn();

        // carl.checker holds APPROVAL_VIEW and APPROVAL_APPROVE and NOTHING else: no
        // INVOICE_VIEW, no SCOPE_OVERRIDE, no seat on this account. InvoiceService.cancel runs
        // requireInBook and regionAccess.requireManage, and both would refuse him. They do not,
        // because the replay runs as mary.maker — which is the whole point of asMaker (B2).
        mockMvc.perform(post("/api/approvals/" + jsonLong(held, "pendingChangeId") + "/approve")
                        .with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void theAuditRowsTheMutatorWritesOnApprovalNameTheMaker() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        Payment paid = paymentRepository.findAll().get(0);
        List<AuditLog> onThePayment = auditService.historyFor("PAYMENT", paid.getId());
        assertThat(onThePayment).extracting(AuditLog::getAction).contains("PAYMENT_RECORDED");
        // The money is the maker's doing; the approver's name is on the CHANGE_APPROVED row
        // beside it, and between the two the whole four-eyes story is readable (B2).
        assertThat(onThePayment).filteredOn(a -> "PAYMENT_RECORDED".equals(a.getAction()))
                .extracting(AuditLog::getChangedByUserId)
                .containsExactly(maker.getId());
        assertThat(auditService.historyFor("PAYMENT", paid.getId()))
                .filteredOn(a -> "CHANGE_APPROVED".equals(a.getAction()))
                .extracting(AuditLog::getChangedByUserId)
                .containsExactly(checker.getId());
    }

    // -------------------------------------------------------------------- who may not decide

    @Test
    void theMakerCannotApproveAChangeTheyRaised() throws Exception {
        User both = user("bea.both", bothRole().getName());
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult held = mockMvc.perform(post("/api/payments").with(as(both))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(acme, "8000.00", List.of()))))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");

        // The panel says so before the button is pressed: "I raised it" and "I may decide it
        // here" are two separate facts, and the UI needs both to say why there is no button (B2).
        mockMvc.perform(get("/api/approvals/" + changeId).with(as(both)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.cannotDecideReason").isNotEmpty());

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(both))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(paymentRepository.count()).isZero();
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    @Test
    void aGlobalApproverStillCannotApproveAChangeTheyRaised() throws Exception {
        assertThat(admin.getRole().getPrivileges()).extracting(Privilege::getName)
                .contains(Privileges.APPROVAL_APPROVE_ANY);
        threshold(defaultRegion().getId(), THOUSAND, true);

        MvcResult held = mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(acme, "8000.00", List.of()))))
                .andExpect(status().isAccepted())
                .andReturn();

        // APPROVAL_APPROVE_ANY is break-glass for WHERE, never for WHO. It is not a waiver of
        // "approval from someone else" and never becomes one (B2).
        mockMvc.perform(post("/api/approvals/" + jsonLong(held, "pendingChangeId") + "/approve")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void anApproverWithNoRightOfAnyKindInThatRecordsRegionGetsNotFound() throws Exception {
        Region west = region("WEST");
        User elsewhere = user("wendy.west", checkerRole().getName());
        revokeRegionGrants(elsewhere);
        grantTo(elsewhere, west.getId(), RegionRight.VIEW);
        grantTo(elsewhere, west.getId(), RegionRight.APPROVE);

        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        // A change on a record in a branch she holds nothing in answers exactly as a missing one
        // does, on the read and on the write alike. 403 would confirm that a change with that id
        // exists (AUTH-08, B2).
        mockMvc.perform(get("/api/approvals/" + changeId).with(as(elsewhere)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(elsewhere))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void aDecidedChangeInABranchTheCallerHoldsNothingInStillAnswersAsAMissingOne() throws Exception {
        Region west = region("WEST");
        User elsewhere = user("wanda.west", checkerRole().getName());
        revokeRegionGrants(elsewhere);
        grantTo(elsewhere, west.getId(), RegionRight.VIEW);
        grantTo(elsewhere, west.getId(), RegionRight.APPROVE);

        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // "This change has already been decided" would tell her that a change with this id exists
        // and is no longer waiting — walked over the id space, that is a census of the company's
        // decided approvals taken from a branch she holds nothing in. Whether it is decided is a
        // question only somebody who may see it at all gets an answer to (AUTH-08, B2).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(elsewhere))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/approvals/" + changeId + "/reject").with(as(elsewhere))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"no\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/approvals/" + changeId + "/withdraw").with(as(elsewhere))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        // And the sentence still reaches the person it was written for.
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This change has already been decided"));
    }

    @Test
    void anApproverWhoCanSeeTheRegionButCannotApproveInItGetsForbidden() throws Exception {
        User seer = user("sam.seer", checkerRole().getName());
        revokeRegionGrants(seer);
        grantTo(seer, defaultRegion().getId(), RegionRight.VIEW);

        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        // He can reach it, so pretending it is not there would be a lie he can disprove: the
        // ACTION is what is refused, and that has always been 403 here (B2, D-46).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(seer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(paymentRepository.count()).isZero();
    }

    // ---------------------------------------------------------- the maker, after the fact

    @Test
    void aChangeRaisedBySomebodySinceDeactivatedCannotBeApproved() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        User gone = userRepository.findById(maker.getId()).orElseThrow();
        gone.setActive(false);
        userRepository.saveAndFlush(gone);

        // Dismissing somebody has to stop their queued money, not let it through later (B2).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The person who raised this change is no longer active"));

        assertThat(paymentRepository.count()).isZero();
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    @Test
    void aChangeRaisedBySomebodyWhoHasSinceLostTheRegionCannotBeApproved() throws Exception {
        Invoice big = invoiceFor(acme, 200);
        threshold(defaultRegion().getId(), THOUSAND, true);
        MvcResult held = mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel")
                        .with(as(maker)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");

        revokeRegionGrants(maker);

        // The replay runs as the maker, so the maker's lost MANAGE grant refuses it. Explicit and
        // not accidental: the change STAYS PENDING and is never auto-rejected, because losing a
        // grant is not a judgement on the change (B2, B1 INTEGRATION).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    @Test
    void aChangeRaisedByTheAutomationEngineWithNoPersonBehindItCanBeApprovedByAnyone() throws Exception {
        Invoice big = invoiceFor(acme, 200);
        Invoice fresh = invoiceRepository.findById(big.getId()).orElseThrow();

        // Composed by hand because the engine that will raise one lands in A5. What is being
        // tested is the null maker, not how it got there: there is nobody to swap to, nobody to
        // exclude, and everybody is therefore someone else (B2, A5 INTEGRATION).
        PendingChange raisedByNobody = pendingChangeRepository.saveAndFlush(PendingChange.builder()
                .action(PendingAction.INVOICE_CANCEL)
                .targetType(PendingTargetType.INVOICE)
                .targetId(fresh.getId())
                .customerId(acme.getId())
                .regionId(defaultRegion().getId())
                .exposure(fresh.getBalance())
                .thresholdApplied(new BigDecimal(THOUSAND))
                .payloadJson("{}")
                .beforeJson(null)
                .summary("Cancel invoice " + fresh.getInvoiceNumber())
                .targetVersion(fresh.getVersion())
                .status(PendingChangeStatus.PENDING)
                .requestedByUserId(null)
                .build());

        mockMvc.perform(post("/api/approvals/" + raisedByNobody.getId() + "/approve")
                        .with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findById(raisedByNobody.getId()).orElseThrow()
                .getDecidedByUserId()).isEqualTo(admin.getId());
    }

    // ----------------------------------------------------------- reject, withdraw, and twice

    @Test
    void rejectingAPendingChangeLeavesTheRecordUntouchedAndItCannotBeApprovedAfterwards() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        // A rejection without a reason is a rejection nobody can act on (B2).
        mockMvc.perform(post("/api/approvals/" + changeId + "/reject").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/approvals/" + changeId + "/reject").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNotes\":\"We do not take cash over a lakh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("REJECTED"))
                .andExpect(jsonPath("$.result").doesNotExist());

        PendingChange rejected = pendingChangeRepository.findById(changeId).orElseThrow();
        assertThat(rejected.getDecidedAt()).isNotNull();
        assertThat(rejected.getDecidedByUserId()).isEqualTo(checker.getId());
        assertThat(rejected.getDecisionNotes()).isEqualTo("We do not take cash over a lakh");
        // Rejected releases the record: pending_key is nulled by the callback, so the account is
        // free for somebody to raise the change again (B2).
        assertThat(rejected.getPendingKey()).isNull();
        assertThat(paymentRepository.count()).isZero();

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This change has already been decided"));
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void withdrawingIsTheMakersOwnOrAnApproversInThatRegionAndNobodyElses() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        User bystander = user("bob.bystander", viewerRole().getName());

        Long mine = heldPayment("8000.00");
        mockMvc.perform(post("/api/approvals/" + mine + "/withdraw").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("WITHDRAWN"));
        PendingChange withdrawn = pendingChangeRepository.findById(mine).orElseThrow();
        assertThat(withdrawn.getDecidedAt()).isNotNull();

        Long theirs = heldPayment("9000.00");
        // Seeing a change is not deciding it, and taking it off somebody's queue is deciding it.
        mockMvc.perform(post("/api/approvals/" + theirs + "/withdraw").with(as(bystander))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(pendingChangeRepository.findById(theirs).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);

        mockMvc.perform(post("/api/approvals/" + theirs + "/withdraw").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("WITHDRAWN"));
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void decidingAChangeTwiceAppliesItOnce() throws Exception {
        threshold(defaultRegion().getId(), THOUSAND, true);
        Long changeId = heldPayment("8000.00");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // The second approver is refused by the status guard under the row's own write lock, and
        // with @Version behind it that is what makes two approvers safe rather than lucky (B2).
        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(creditOf(acme)).isEqualByComparingTo("8000.00");
    }

    // ------------------------------------------------------------------------------ fixtures

    private Long heldPayment(String amount) throws Exception {
        MvcResult held = mockMvc.perform(post("/api/payments").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(acme, amount, List.of()))))
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

    private PaymentDtos.CreatePaymentRequest payment(Customer c, String amount, List<Long> invoiceIds) {
        return new PaymentDtos.CreatePaymentRequest(c.getId(), new BigDecimal(amount), "NEFT",
                null, invoiceIds, collections.getId(), null);
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

    private void grantTo(User u, Long regionId, RegionRight right) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(regionId).right(right).build());
    }

    /** Everything a person needs to move money on an account, and no approval right at all. */
    private Role makerRole() {
        return roleWith("MONEY_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.POC_ASSIGN,
                Privileges.SCOPE_OVERRIDE, Privileges.APPROVAL_VIEW);
    }

    /** An approver and NOTHING else: deliberately unable to see, reach or write any of the
     *  records they decide on, which is what makes the asMaker test mean anything (B2). */
    private Role checkerRole() {
        return roleWith("CHECKER_ONLY", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role bothRole() {
        return roleWith("MAKER_AND_CHECKER",
                Privileges.CUSTOMER_VIEW, Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.POC_VIEW, Privileges.POC_ASSIGN, Privileges.SCOPE_OVERRIDE,
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role viewerRole() {
        return roleWith("QUEUE_WATCHER", Privileges.APPROVAL_VIEW);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalDecisionTest")
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
