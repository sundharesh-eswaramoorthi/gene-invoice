package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One click that holds rows, and one click that decides them (B2).
 *
 * <p>The test this file exists for is {@code aBulkRowHeldForApprovalIsReportedAsPendingAndNotAsSkipped}.
 * {@code BulkExecutor.eligibility} turns every BadRequestException into "skipped — did not
 * qualify", so the day somebody makes PendingApprovalException a BadRequestException, every held
 * bulk row starts reporting the one sentence that says the opposite of what happened to it, AND
 * nothing is parked at all. Neither failure looks like a failure from outside: the request still
 * answers 200 with a tidy-looking sheet.
 *
 * <p>Every assertion about a held bulk row is really an assertion about a ROLLBACK followed by a
 * write in a transaction of its own — the row did not change, and the change did get written
 * down. A test that only counted the buckets would pass against a run that cancelled the invoices
 * and then called them pending.
 */
class ApprovalBulkTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired ApprovalProperties approvalProperties;
    @Autowired BulkExecutor bulkExecutor;

    static final String LAKH = "100000.00";

    /** 2,000 x 100.00 = 2,00,000.00, comfortably above LAKH: an unpaid invoice's cancel is
     *  measured at its balance (InvoiceService.cancel), so every one of these is held. */
    static final int BIG = 2_000;

    /** 50 x 100.00 = 5,000.00, comfortably below it. */
    static final int SMALL = 50;

    User admin;
    User maker;
    User checker;
    User collections;
    Customer acme;
    Product widget;
    int configuredLimit;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        maker = user("mona.maker", makerRole().getName());
        checker = user("chandra.checker", checkerRole().getName());
        collections = user("cora.collections4", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        configuredLimit = approvalProperties.bulkPendingLimit();
    }

    /** ApprovalProperties is a singleton in a cached context, so a test that lowers the limit to
     *  something it can actually reach must put it back or every later test in the run inherits
     *  it (B2). */
    @AfterEach
    void restoreTheConfiguredLimit() {
        approvalProperties.setBulkPendingLimit(configuredLimit);
    }

    // --------------------------------------------------------------- the fourth bucket (B2)

    @Test
    void aBulkCancelAboveTheThresholdReportsEveryRowAsPendingAndCancelsNoneOfThem() throws Exception {
        // The invoices are raised BEFORE the limit exists, or raising them would be held too:
        // INVOICE_CREATE is gated at the same limit as INVOICE_CANCEL (B2).
        List<Long> ids = List.of(invoice(BIG).getId(), invoice(BIG).getId(), invoice(BIG).getId());
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(ids))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.pending.length()").value(3))
                .andExpect(jsonPath("$.pendingLimitReached").value(false))
                .andExpect(jsonPath("$.pending[0].reason").value(
                        org.hamcrest.Matchers.startsWith("Sent for approval as change #")));

        // Not one of them changed, which is what "held" has to mean.
        for (Long id : ids) {
            assertThat(invoiceRepository.findById(id).orElseThrow().getStatus())
                    .isNotEqualTo(InvoiceStatus.CANCELLED);
        }
        List<PendingChange> raised = pendingChangeRepository.findAll();
        assertThat(raised).hasSize(3);
        assertThat(raised).allMatch(c -> c.getAction() == PendingAction.INVOICE_CANCEL);
        assertThat(raised).allMatch(c -> c.getStatus() == PendingChangeStatus.PENDING);
        // One click, one batch: this is what makes them decidable in one act afterwards.
        assertThat(raised.stream().map(PendingChange::getBatchId).distinct().toList()).hasSize(1);
        assertThat(raised.get(0).getBatchId()).isNotBlank().hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void aBulkRowHeldForApprovalIsReportedAsPendingAndNotAsSkipped() throws Exception {
        Long held = invoice(BIG).getId();
        Long through = invoice(SMALL).getId();
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(List.of(held, through)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.succeeded.length()").value(1))
                .andExpect(jsonPath("$.succeeded[0]").value(through))
                // The whole point: skipped means "did not qualify", and this row qualified.
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.pending[0].id").value(held));

        assertThat(invoiceRepository.findById(through).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(held).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
    }

    /**
     * A held bulk row rolls its own transaction back and then writes only {@code pending_changes}
     * in a transaction of its own. {@code pending_changes} is deliberately NOT a mirrored entity
     * — B3 answers "which approvals were outstanding then" off requestedAt/decidedAt, which are
     * already interval-shaped — so B3's drain has nothing to do in that second transaction, and
     * the rolled-back first one leaves no version of an invoice that did not change.
     *
     * <p>Mandated by the blueprint alongside
     * {@code HistoryWriteTest#aBulkRowWritesItsOwnMirrorRowsInItsOwnTransaction}: the interaction
     * between B2's park and B3's transaction manager is benign, and is now required to stay so
     * (B2, B3 INTEGRATION).
     */
    @Test
    void aHeldBulkRowWritesNoMirrorRow() throws Exception {
        Long held = invoice(BIG).getId();
        Long through = invoice(SMALL).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        invoiceHistoryRepository.deleteAll();

        mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(List.of(held, through)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.succeeded.length()").value(1));

        // The row that went through has a version; the row that was parked has none, because
        // nothing about it changed and a mirror row for it would be a lie about the past.
        assertThat(invoiceHistoryRepository.findAll()).extracting(InvoiceHistory::getId)
                .containsExactly(through);
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
    }

    @Test
    void aBulkRunStopsQueueingApprovalsAtTheConfiguredLimitAndSaysSo() throws Exception {
        approvalProperties.setBulkPendingLimit(2);
        List<Long> ids = List.of(invoice(BIG).getId(), invoice(BIG).getId(), invoice(BIG).getId());
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(ids))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.pending.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.skipped[0].reason").value(BulkExecutor.PENDING_LIMIT_REACHED))
                // Said out loud on the envelope as well as per row: a caller who is not told
                // would read "2 of 3" as "one did not qualify" and move on (B2).
                .andExpect(jsonPath("$.pendingLimitReached").value(true))
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(0));

        assertThat(pendingChangeRepository.count()).isEqualTo(2);
        // The row over the limit was not acted on either: not sent is not the same as done.
        assertThat(invoiceRepository.findById(ids.get(2)).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void aBulkRowWhoseRecordAlreadyHasAChangeWaitingIsSkippedAndNotQueuedTwice() throws Exception {
        Long spokenFor = invoice(BIG).getId();
        Long free = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices/" + spokenFor + "/cancel").with(as(maker)))
                .andExpect(status().isAccepted());
        assertThat(pendingChangeRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/invoices/bulk").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(List.of(spokenFor, free)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].id").value(spokenFor))
                .andExpect(jsonPath("$.skipped[0].reason").value(
                        org.hamcrest.Matchers.startsWith(
                                "A change on this record is already waiting for approval")))
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.pending[0].id").value(free));

        // One waiting change per record, and the second run added none of its own to that one.
        assertThat(pendingChangeRepository.findByTargetTypeAndTargetIdAndStatus(
                PendingTargetType.INVOICE, spokenFor, PendingChangeStatus.PENDING)).hasSize(1);
        assertThat(pendingChangeRepository.count()).isEqualTo(2);
    }

    /**
     * The same rule when Java did not catch it: the gate's in-transaction exists() is not the
     * backstop, uq_pending_open is, and the run must survive losing that race rather than
     * reporting a database error to somebody who cancelled some invoices (B2).
     *
     * <p>Driven through BulkExecutor directly because two runs racing one record cannot be
     * arranged sequentially over HTTP — the gate's own existsByPendingKey IS the constraint's
     * predicate, so it always answers first. This is the only test in the suite that reaches the
     * catch (DataIntegrityViolationException) arm at all.
     *
     * <p>TWO rows and not one, on purpose: what a green H2 run cannot show is that the run SURVIVES
     * the violation. On Postgres a unique violation aborts the enclosing transaction and every
     * later statement on it fails with "current transaction is aborted", so the row after the
     * collision is the assertion that matters — its park opens a transaction of its own on a
     * connection the template has already rolled back and returned (B2).
     */
    @Test
    void aChangeAnotherRunWroteFirstIsSkippedAsAlreadyWaitingAndTheRestOfTheRunGoesOn() throws Exception {
        Long spokenFor = invoice(BIG).getId();
        Long free = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        mockMvc.perform(post("/api/invoices/" + spokenFor + "/cancel").with(as(maker)))
                .andExpect(status().isAccepted());

        actAs(maker);
        BulkDtos.BulkResult result = bulkExecutor.run(
                new BulkDtos.BulkRequest("CANCEL", List.of(spokenFor, free), null, null, null, null),
                List.of(spokenFor, free), false,
                id -> { throw new PendingApprovalException(colliding(id)); });

        assertThat(result.failed()).isEmpty();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).id()).isEqualTo(spokenFor);
        assertThat(result.skipped().get(0).reason()).isEqualTo(BulkExecutor.ALREADY_WAITING);
        assertThat(result.pending()).hasSize(1);
        assertThat(result.pending().get(0).id()).isEqualTo(free);
        assertThat(pendingChangeRepository.count()).isEqualTo(2);
    }

    // --------------------------------------------------------- one batch, one decision (B2)

    @Test
    void everyChangeABulkRunRaisedCanBeDecidedInOneActByItsBatchId() throws Exception {
        Long first = invoice(BIG).getId();
        Long second = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        String batchId = heldBatch(List.of(first, second));

        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("APPROVE"))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.pending.length()").value(0));

        // The saves that did not happen have now happened, both of them, in one act.
        assertThat(invoiceRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findByBatchIdAndStatus(batchId, PendingChangeStatus.PENDING))
                .isEmpty();
        assertThat(pendingChangeRepository.findAll()).allMatch(
                c -> c.getStatus() == PendingChangeStatus.APPROVED
                        && checker.getId().equals(c.getDecidedByUserId())
                        && c.getDecidedAt() != null);
    }

    @Test
    void oneChangeThatCanNoLongerBeAppliedDoesNotTakeTheRestOfTheBatchWithIt() throws Exception {
        Long moved = invoice(BIG).getId();
        Long still = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        String batchId = heldBatch(List.of(moved, still));
        Long movedChange = changeFor(moved);

        // A payment lands on one of the two while they wait, so its row version is no longer the
        // one the change was composed against. That is a 409 for that change and nothing else.
        actAs(admin);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("1000.00"), "NEFT", null, List.of(moved),
                collections.getId(), null));

        MvcResult result = mockMvc.perform(
                        post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(1))
                .andExpect(jsonPath("$.failed.length()").value(1))
                // The buckets of a batch decision hold CHANGE ids, not record ids: it is changes
                // that are being decided (B2).
                .andExpect(jsonPath("$.failed[0].id").value(movedChange))
                .andExpect(jsonPath("$.failed[0].reason").value(
                        org.hamcrest.Matchers.containsString("changed while the change was waiting")))
                .andReturn();
        assertThat(body(result)).doesNotContain("\"succeeded\":[" + movedChange + "]");

        assertThat(invoiceRepository.findById(still).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoiceRepository.findById(moved).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        // Refused on live state is not a rejection: nobody judged it, so it keeps waiting.
        assertThat(pendingChangeRepository.findById(movedChange).orElseThrow().getStatus())
                .isEqualTo(PendingChangeStatus.PENDING);
    }

    @Test
    void aBatchDecideSkipsTheChangesTheCallerMayNotDecideAndSaysWhy() throws Exception {
        User both = user("bala.both", bothRole().getName());
        Long first = invoice(BIG).getId();
        Long second = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        String batchId = heldBatch(both, List.of(first, second));

        // An approver who raised them himself. "Approval from someone else" is never waived, and
        // a batch is exactly where somebody would expect their own fifty to slip through (B2).
        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(both))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].reason")
                        .value("You cannot approve a change you raised"))
                .andExpect(jsonPath("$.skipped[1].reason")
                        .value("You cannot approve a change you raised"));

        // The other reason, and a different sentence: somebody who can see the queue in this
        // branch but holds no approval right in it. The row is reported per row rather than the
        // whole request being refused 403, because the two callers' answers differ per change.
        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].reason")
                        .value("You do not hold the approval right in this region"));

        assertThat(invoiceRepository.findById(first).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findByBatchIdAndStatus(batchId, PendingChangeStatus.PENDING))
                .hasSize(2);

        // And the same batch, from somebody entitled to decide it, goes through — so the skip was
        // about the caller and not about the changes.
        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2));
    }

    @Test
    void aBatchRejectionWithoutAReasonIsRefusedRatherThanSkippingEveryRowInTurn() throws Exception {
        Long first = invoice(BIG).getId();
        Long second = invoice(BIG).getId();
        threshold(defaultRegion().getId(), LAKH, true);
        String batchId = heldBatch(List.of(first, second));

        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Say why these changes are being rejected"));

        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SHRUG\"}"))
                .andExpect(status().isBadRequest());

        assertThat(pendingChangeRepository.findByBatchIdAndStatus(batchId, PendingChangeStatus.PENDING))
                .hasSize(2);

        mockMvc.perform(post("/api/approvals/batches/" + batchId + "/decide").with(as(checker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"decisionNotes\":\"Raised by mistake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("REJECT"))
                .andExpect(jsonPath("$.succeeded.length()").value(2));

        assertThat(invoiceRepository.findById(first).orElseThrow().getStatus())
                .isNotEqualTo(InvoiceStatus.CANCELLED);
        assertThat(pendingChangeRepository.findAll()).allMatch(
                c -> c.getStatus() == PendingChangeStatus.REJECTED
                        && "Raised by mistake".equals(c.getDecisionNotes()));
    }

    // ------------------------------------------------------------------------------ fixtures

    /** Runs the bulk cancel, asserts every row was held, and answers the batch they share. */
    private String heldBatch(List<Long> invoiceIds) throws Exception {
        return heldBatch(maker, invoiceIds);
    }

    private String heldBatch(User actor, List<Long> invoiceIds) throws Exception {
        mockMvc.perform(post("/api/invoices/bulk").with(as(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(cancel(invoiceIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.length()").value(invoiceIds.size()));
        List<String> batches = pendingChangeRepository.findAll().stream()
                .map(PendingChange::getBatchId).distinct().toList();
        assertThat(batches).hasSize(1);
        return batches.get(0);
    }

    private Long changeFor(Long invoiceId) {
        return pendingChangeRepository.findByTargetTypeAndTargetIdAndStatus(
                PendingTargetType.INVOICE, invoiceId, PendingChangeStatus.PENDING)
                .get(0).getId();
    }

    /** A change carrying the pending_key of one that is already waiting, so the insert meets
     *  uq_pending_open rather than the gate. Every NOT NULL column is filled by hand because the
     *  gate is deliberately not involved. */
    private PendingChange colliding(Long invoiceId) {
        return PendingChange.builder()
                .action(PendingAction.INVOICE_CANCEL)
                .targetType(PendingTargetType.INVOICE)
                .targetId(invoiceId)
                .customerId(acme.getId())
                .regionId(defaultRegion().getId())
                .exposure(new BigDecimal("200000.00"))
                .thresholdApplied(new BigDecimal(LAKH))
                .payloadJson("{}")
                .summary("Cancel invoice " + invoiceId)
                .status(PendingChangeStatus.PENDING)
                .requestedByUserId(maker.getId())
                .requestedAt(Instant.now())
                .build();
    }

    private BulkDtos.BulkRequest cancel(List<Long> ids) {
        return new BulkDtos.BulkRequest("CANCEL", ids, null, null, null, null);
    }

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private Invoice invoice(int quantity) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00")))));
    }

    /** Everything a person needs to cancel invoices in bulk, and no approval right at all. */
    private Role makerRole() {
        return roleWith("BULK_MAKER",
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE, Privileges.PRODUCT_VIEW,
                Privileges.POC_VIEW, Privileges.SCOPE_OVERRIDE, Privileges.APPROVAL_VIEW);
    }

    private Role checkerRole() {
        return roleWith("BULK_CHECKER", Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    /** Somebody who may raise a change AND approve one — which is the posture the maker rule has
     *  to hold against, because holding both rights is not holding permission to use them on the
     *  same change (B2). */
    private Role bothRole() {
        return roleWith("BULK_MAKER_AND_CHECKER",
                Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                Privileges.PRODUCT_VIEW, Privileges.POC_VIEW, Privileges.SCOPE_OVERRIDE,
                Privileges.APPROVAL_VIEW, Privileges.APPROVAL_APPROVE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by ApprovalBulkTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
