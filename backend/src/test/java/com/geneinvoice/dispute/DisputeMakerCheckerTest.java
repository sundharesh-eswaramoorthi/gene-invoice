package com.geneinvoice.dispute;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalThreshold;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeStatus;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dispute, migrated onto the generalised mechanism (B2).
 *
 * <p>A dispute has always been a maker-checker of its own and has never had either half of the
 * rule. Before this unit the person who opened a dispute could resolve it, and the four mutators
 * with no endpoint of their own — voidPayment, updateAmount, cancelWithRefundForDisputeApplication
 * and replaceItemsForDisputeApplication — were reachable through it with no measurement of what
 * they would move. The app had no dispute-approval test at all.
 *
 * <p>The load-bearing pair is {@code aDisputeApprovalAboveTheThresholdIsItselfHeld...} and
 * {@code theInnerMutatorsAreNotGatedASecondTimeDuringADisputeReplay}: the gate must fire ONCE, on
 * the dispute approval, and produce a DISPUTE_APPROVE change. A gate firing on an inner mutator
 * instead would roll the dispute-status flip back and park a change against the invoice, leaving a
 * dispute PENDING for ever behind an approval nobody can connect to it.
 */
class DisputeMakerCheckerTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired PrivilegeRepository privilegeRepository;

    static final String LAKH = "100000.00";
    static final String SIXTEEN_LAKH = "1600000.00";

    User admin;
    User resolver;
    User checker;
    Customer acme;
    User acmeLogin;
    User collections;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        resolver = user("rita.resolver", resolverRole().getName());
        checker = user("carl.checker", checkerRole().getName());
        acme = customer("Acme Ltd");
        acmeLogin = customerLogin("amy.acme", acme.getId(), role("CUSTOMER"));
        collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    // ------------------------------------------------------------------ the two halves of the rule

    @Test
    void theCustomerWhoOpenedADisputeCannotApproveItThemselves() throws Exception {
        // An operator who adds DISPUTE_MANAGE to the customer role — or any other route by which
        // the opener ends up holding it — used to be able to approve their own dispute outright:
        // requireInBook passes on their own account and RegionAccess waves a customer login
        // through, so nothing in the request stood between them and the money (B2).
        User selfServing = customerLogin("sam.self", acme.getId(), customerResolverRole());
        Invoice inv = invoice(50);
        Dispute d = dispute(selfServing.getId(), DisputeTargetType.INVOICE, inv.getId(),
                "{\"action\":\"update_notes\",\"notes\":\"Agreed by phone\"}");

        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(selfServing))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot approve a change you raised"));

        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.PENDING);

        // And it is about the PERSON and not about the dispute: somebody else resolves the very
        // same one.
        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void anAdminCannotApproveADisputeOnARecordInARegionTheyHoldNothingIn() throws Exception {
        Region west = region("WEST");
        Customer faraway = customerRepository.save(
                Customer.builder().name("Faraway Ltd").region(west).build());
        Invoice inv = invoiceFor(faraway, 50);
        Dispute d = dispute(acmeLogin.getId(), faraway.getId(), DisputeTargetType.INVOICE,
                inv.getId(), "{\"action\":\"cancel\"}");

        // rita.resolver holds DISPUTE_MANAGE, but only in the default branch. A record in a branch
        // she holds nothing in answers exactly as a missing one does — 404 and never 403, because
        // a 403 would tell her the dispute exists (B1, AUTH-08).
        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
    }

    // ------------------------------------------------------------------------------- the one gate

    @Test
    void aDisputeApprovalAboveTheThresholdIsItselfHeldAndTheDisputeStaysPending() throws Exception {
        Invoice big = invoice(20_000);                              // 20,00,000.00
        threshold(defaultRegion().getId(), LAKH, true);
        Dispute d = dispute(acmeLogin.getId(), DisputeTargetType.INVOICE, big.getId(),
                "{\"action\":\"cancel\"}");

        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                // The change is about the DISPUTE and not about the invoice underneath it: an
                // INVOICE_CANCEL_WITH_REFUND row here would mean an inner gate fired and the
                // dispute's own status flip was rolled back with it (B2).
                .andExpect(jsonPath("$.action").value("DISPUTE_APPROVE"))
                .andExpect(jsonPath("$.targetType").value("DISPUTE"))
                .andExpect(jsonPath("$.targetId").value(d.getId()))
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.regionId").value(defaultRegion().getId()))
                .andExpect(jsonPath("$.exposure").value(2000000.00))
                .andExpect(jsonPath("$.thresholdApplied").value(100000.00));

        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.PENDING);
        assertThat(reload(d).getResolvedByUserId()).isNull();
        Invoice after = invoiceRepository.findById(big.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(after.getTotal()).isEqualByComparingTo("2000000.00");

        List<PendingChange> waiting = pendingChangeRepository.findAll();
        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).getAction()).isEqualTo(PendingAction.DISPUTE_APPROVE);
        assertThat(waiting.get(0).getTargetType()).isEqualTo(PendingTargetType.DISPUTE);
        assertThat(waiting.get(0).getRequestedByUserId()).isEqualTo(resolver.getId());
        assertThat(waiting.get(0).getPendingKey()).isEqualTo("DISPUTE:" + d.getId());
    }

    @Test
    void approvingTheHeldDisputeAppliesTheChangeAndResolvesTheDisputeInOneAct() throws Exception {
        Invoice big = invoice(20_000);
        threshold(defaultRegion().getId(), LAKH, true);
        Dispute d = dispute(acmeLogin.getId(), DisputeTargetType.INVOICE, big.getId(),
                "{\"action\":\"cancel\"}");

        MvcResult held = mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve")
                        .with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DisputeDtos.ResolveDisputeRequest("Goodwill", null))))
                .andExpect(status().isAccepted())
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("APPROVED"))
                .andExpect(jsonPath("$.change.decidedByUserId").value(checker.getId()))
                // One act: the replay is the whole of DisputeService.approve, so the result the
                // approver is handed back is the resolved dispute itself (B2).
                .andExpect(jsonPath("$.result.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.id").value(d.getId()));

        Dispute resolved = reload(d);
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.APPROVED);
        // The maker is the accountable person, not the approver: asMaker replays under rita's
        // principal and the dispute records HER as the resolver (B2).
        assertThat(resolved.getResolvedByUserId()).isEqualTo(resolver.getId());
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getAdminNotes()).isEqualTo("Goodwill");

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.APPROVED);
    }

    @Test
    void theInnerMutatorsAreNotGatedASecondTimeDuringADisputeReplay() throws Exception {
        Invoice inv = invoice(20_000);                              // 20,00,000.00
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("500000.00"), "NEFT", null,
                List.of(inv.getId()), collections.getId(), null));
        threshold(defaultRegion().getId(), LAKH, true);
        Dispute d = dispute(acmeLogin.getId(), DisputeTargetType.PAYMENT, paid.getId(),
                "{\"action\":\"void\"}");

        MvcResult held = mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve")
                        .with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("DISPUTE_APPROVE"))
                .andExpect(jsonPath("$.exposure").value(500000.00))
                .andReturn();
        Long changeId = jsonLong(held, "pendingChangeId");
        assertThat(pendingChangeRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/approvals/" + changeId + "/approve").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // The whole point: the replay walks straight through PaymentService.voidPayment's own
        // gate, which would otherwise hold a 5,00,000.00 void a second time against a 1,00,000.00
        // limit and park a PAYMENT_VOID change nobody asked for. ONE change, and it is decided.
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.findById(changeId).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.APPROVED);
        assertThat(pendingChangeRepository.findByTargetTypeAndTargetIdAndStatus(
                PendingTargetType.PAYMENT, paid.getId(), PendingChangeStatus.PENDING)).isEmpty();

        assertThat(paymentRepository.findById(paid.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0.00");
        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.APPROVED);
    }

    // ------------------------------------------------------------------------ the missing guard

    @Test
    void aDisputeProposingAThreeDecimalAmountIsRefusedBeforeAnyMoneyMoves() throws Exception {
        Invoice inv = invoice(50);                                  // 5,000.00
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("8000.00"), "NEFT", null,
                List.of(inv.getId()), collections.getId(), null));
        assertThat(creditBalance()).isEqualByComparingTo("3000.00");

        // No threshold: nothing here is about the gate. A proposed change arrives as free-form
        // JSON that no @Digits annotation ever saw, and a third decimal place used to travel all
        // the way into updateAmount (B2).
        Dispute d = dispute(acmeLogin.getId(), DisputeTargetType.PAYMENT, paid.getId(),
                "{\"action\":\"update_amount\",\"amount\":1000.123}");

        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Amount must have at most 2 decimal places"));

        assertThat(reload(d).getStatus()).isEqualTo(DisputeStatus.PENDING);
        Payment after = paymentRepository.findById(paid.getId()).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo("8000.00");
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("5000.00");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(creditBalance()).isEqualByComparingTo("3000.00");
        assertThat(pendingChangeRepository.count()).isZero();
    }

    // ---------------------------------------------------------------------------- the measurement

    @Test
    void aDisputeCancelIsMeasuredAsTheLargerOfTheInvoicesTotalAndItsPaidAmount() throws Exception {
        Invoice inv = invoice(20_000);                              // total 20,00,000.00
        paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("500000.00"), "NEFT", null,
                List.of(inv.getId()), collections.getId(), null));
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1500000.00");

        // 16,00,000.00 sits ABOVE the balance and BELOW the total, so only a measure that reads
        // total.max(paidAmount) crosses it. A rule that scored the balance — what is still owed —
        // would let a 20,00,000.00 cancellation and a 5,00,000.00 refund through unseen (B2).
        threshold(defaultRegion().getId(), SIXTEEN_LAKH, true);
        Dispute d = dispute(acmeLogin.getId(), DisputeTargetType.INVOICE, inv.getId(),
                "{\"action\":\"cancel\"}");

        mockMvc.perform(post("/api/disputes/" + d.getId() + "/approve").with(as(resolver))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exposure").value(2000000.00))
                .andExpect(jsonPath("$.thresholdApplied").value(1600000.00));

        // The same figure the inner mutator would have used, which is what makes ONE gate enough:
        // InvoiceService.cancelWithRefundForDisputeApplication measures total.max(paidAmount) too.
        Invoice live = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(live.getTotal().max(live.getPaidAmount())).isEqualByComparingTo("2000000.00");
    }

    // ------------------------------------------------------------------------------- fixtures

    private Dispute dispute(Long openedBy, DisputeTargetType type, Long targetId, String changeJson) {
        return dispute(openedBy, acme.getId(), type, targetId, changeJson);
    }

    /** Raised through the shared body every dispute goes through, so the fixture is a real one. */
    private Dispute dispute(Long openedBy, Long customerId, DisputeTargetType type,
                            Long targetId, String changeJson) {
        actAs(admin);
        return disputeService.openAs(openedBy, customerId, type, targetId,
                "This is wrong", changeJson);
    }

    private Dispute reload(Dispute d) {
        return disputeRepository.findById(d.getId()).orElseThrow();
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private Invoice invoice(int quantity) {
        return invoiceFor(acme, quantity);
    }

    private Invoice invoiceFor(Customer c, int quantity) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                c.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00")))));
    }

    private BigDecimal creditBalance() {
        return customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance();
    }

    private User customerLogin(String username, Long customerId, Role role) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.local")
                .fullName(username.toUpperCase())
                .password(passwordEncoder.encode("password"))
                .role(role)
                .customerId(customerId)
                .active(true)
                .build());
    }

    /** Staff who decide disputes: everything the approval path touches, and no approval right. */
    private Role resolverRole() {
        return roleWith("DISPUTE_RESOLVER",
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.SCOPE_OVERRIDE,
                Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE,
                Privileges.APPROVAL_VIEW);
    }

    /** An approver and nothing else, so the replay has to rebuild the maker to get anywhere. */
    private Role checkerRole() {
        return roleWith("DISPUTE_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    /** A customer login an operator has also given DISPUTE_MANAGE — the shape the first half of
     *  the rule exists for. A customer keeps every privilege its role gives, because regions
     *  neither widen nor narrow a customer (B1). */
    private Role customerResolverRole() {
        return roleWith("CUSTOMER_RESOLVER",
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.DISPUTE_CREATE, Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by DisputeMakerCheckerTest")
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
