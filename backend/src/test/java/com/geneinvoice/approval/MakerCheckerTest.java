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
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.Region;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate, end to end: twelve money mutators now ask before they write, and above the region's
 * limit the write does not happen at all (B2).
 *
 * <p>Every assertion about a held save is really an assertion about a ROLLBACK. The gate throws
 * out of the mutator's own transaction, so what is being proved here is that nothing survives it —
 * no payment row, no invoice number, no credit balance move — and that the pending row written
 * afterwards by GlobalExceptionHandler, in a transaction of its own, does survive. A test that
 * only checked the 202 would pass against a gate that wrote the money and then apologised (B2).
 *
 * <p>MoneyOracleTest is the control beside this one: it pins what the same three writes do when
 * they are NOT held, and it must stay byte-identical green.
 */
class MakerCheckerTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired ApprovalProperties approvalProperties;
    @Autowired AuditService auditService;
    @Autowired ApprovalService approvalService;

    static final LocalDate TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1);

    static final String LAKH = "100000.00";
    static final String TWELVE_FIFTY = "1250000.00";

    User admin;
    User collections;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    // ------------------------------------------------------------------ the gate, at its edges

    @Test
    void aPaymentBelowTheRegionThresholdIsRecordedStraightAway() throws Exception {
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment("50000.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(50000.00));

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void aPaymentAboveTheRegionThresholdIsNotRecordedAndAnswers202WithAPendingChange() throws Exception {
        threshold(defaultRegion().getId(), LAKH, true);

        MvcResult result = mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.action").value("PAYMENT_RECORD"))
                .andExpect(jsonPath("$.targetType").value("PAYMENT"))
                // A create has no record yet: the account is what makes the waiting change
                // visible on something (B2).
                .andExpect(jsonPath("$.targetId").doesNotExist())
                .andExpect(jsonPath("$.customerId").value(acme.getId()))
                .andExpect(jsonPath("$.regionId").value(defaultRegion().getId()))
                .andExpect(jsonPath("$.regionName").value(defaultRegion().getName()))
                .andExpect(jsonPath("$.exposure").value(1250000.00))
                .andExpect(jsonPath("$.thresholdApplied").value(100000.00))
                .andExpect(jsonPath("$.path").value("/api/payments"))
                .andReturn();

        Long changeId = jsonLong(result, "pendingChangeId");
        assertThat(body(result))
                .contains("\"link\":\"/approvals/" + changeId + "\"")
                .contains("₹12,50,000.00 is above the " + defaultRegion().getName()
                        + " approval limit of ₹1,00,000.00.")
                .contains("has not taken effect");

        // The save did not happen. This is the whole feature (B2).
        assertThat(paymentRepository.count()).isZero();

        PendingChange held = pendingChangeRepository.findById(changeId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
        assertThat(held.getPendingKey()).isNull();          // a create is never spoken for
        assertThat(held.getRequestedByUserId()).isEqualTo(admin.getId());
        assertThat(held.getPayloadVersion()).isEqualTo(PendingChange.PAYLOAD_VERSION);
        assertThat(held.getPayloadJson()).contains("\"amount\":1250000.00");

        // The business transaction rolled back and took its own audit rows with it, so the only
        // trace a maker leaves is this one — and it has to be there (B2).
        List<AuditLog> trail = auditService.historyFor("CUSTOMER", acme.getId());
        assertThat(trail).extracting(AuditLog::getAction).contains("CHANGE_REQUESTED");
    }

    @Test
    void theCreditBalanceAndEveryInvoiceAreUntouchedWhileThePaymentIsPending() throws Exception {
        Invoice inv = invoice(50);                                  // 50 x 100.00 = 5,000.00
        paymentService.record(payment("8000.00", List.of(inv.getId())));

        BigDecimal creditBefore = creditBalance();
        Invoice invoiceBefore = invoiceRepository.findById(inv.getId()).orElseThrow();
        BigDecimal paidBefore = invoiceBefore.getPaidAmount();
        InvoiceStatus statusBefore = invoiceBefore.getStatus();
        long paymentsBefore = paymentRepository.count();
        assertThat(creditBefore).isEqualByComparingTo("3000.00");

        threshold(defaultRegion().getId(), LAKH, true);
        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY, List.of(inv.getId())))))
                .andExpect(status().isAccepted());

        Invoice after = invoiceRepository.findById(inv.getId()).orElseThrow();
        assertThat(after.getPaidAmount()).isEqualByComparingTo(paidBefore);
        assertThat(after.getBalance()).isEqualByComparingTo(invoiceBefore.getBalance());
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(creditBalance()).isEqualByComparingTo(creditBefore);
        assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
    }

    @Test
    void noInvoiceNumberIsConsumedByAnInvoiceCreateThatIsHeld() throws Exception {
        Invoice first = invoice(10);
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invoiceRequest(20_000))))       // 20,000 x 100 = 20,00,000
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("INVOICE_CREATE"))
                .andExpect(jsonPath("$.exposure").value(2000000.00));

        assertThat(invoiceRepository.count()).isEqualTo(1);

        actAs(admin);
        Invoice next = invoiceService.create(invoiceRequest(10));
        // InvoiceNumbers.next() is MANDATORY and joins the mutator's transaction, so the gate's
        // rollback undid the sequence increment and no number was burned (B2).
        assertThat(suffix(next.getInvoiceNumber())).isEqualTo(suffix(first.getInvoiceNumber()) + 1);
    }

    // ---------------------------------------------------------------- what "the amount" means

    @Test
    void aPaymentAmountEditFromTenLakhToTenLakhAndOneRupeeIsMeasuredAsTenLakhAndOne() {
        Payment p = paymentService.record(payment("1000000.00"));
        threshold(defaultRegion().getId(), "1000000.00", true);

        actAs(admin);
        PendingApprovalException held = catchThrowableOfType(
                () -> paymentService.updateAmount(p.getId(), new BigDecimal("1000001.00"), null, null),
                PendingApprovalException.class);

        // A delta rule would have scored this at one rupee and waved it through. max(before,
        // after) scores it at the larger side, because this method un-applies the whole old
        // amount and re-applies the new one across every invoice of the customer (B2).
        assertThat(held).isNotNull();
        assertThat(held.change().getExposure()).isEqualByComparingTo("1000001.00");
        assertThat(held.change().getAction()).isEqualTo(PendingAction.PAYMENT_UPDATE_AMOUNT);
        assertThat(held.change().getTargetId()).isEqualTo(p.getId());
        assertThat(held.change().getTargetVersion()).isNotNull();
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("1000000.00");
    }

    @Test
    void cancellingALargeUnpaidInvoiceNeedsApprovalAlthoughTheRequestCarriesNoAmount() throws Exception {
        Invoice big = invoice(20_000);                                // 20,00,000.00, unpaid
        threshold(defaultRegion().getId(), LAKH, true);

        mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("INVOICE_CANCEL"))
                .andExpect(jsonPath("$.targetId").value(big.getId()))
                // Read off the RECORD: POST /cancel carries no body at all, which is the case a
                // threshold on the request payload misses entirely (B2).
                .andExpect(jsonPath("$.exposure").value(2000000.00));

        assertThat(invoiceRepository.findById(big.getId()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void movingAPromisesCollectionPocDoesNotAskForApprovalAlthoughThePromiseIsLarge() {
        User other = user("carl.collections", DataSeeder.ROLE_COLLECTION_POC);
        PromiseDtos.PromiseDto big = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("2000000.00"), TOMORROW, collections.getId(),
                "a large promise", List.of()));
        threshold(defaultRegion().getId(), LAKH, true);

        actAs(admin);
        promiseService.reassignCollectionPoc(big.id(), other.getId());

        // reassignCollectionPoc self-invokes update() with the promise's OWN amount, so the
        // exposure is zero and nothing is held. Without that branch every POC move on a large
        // promise would fill the approval queue with changes that move no money (B2).
        PaymentPromise fresh = promiseRepository.findById(big.id()).orElseThrow();
        assertThat(fresh.getCollectionPoc().getId()).isEqualTo(other.getId());
        assertThat(fresh.getAmount()).isEqualByComparingTo("2000000.00");
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void deletingACustomerIsHeldWhateverTheCreditBalance() throws Exception {
        Customer empty = customer("Nothing Owed Ltd");
        assertThat(empty.getCreditBalance()).isEqualByComparingTo("0.00");
        assertThat(approvalThresholdRepository.count()).isZero();

        MvcResult result = mockMvc.perform(delete("/api/customers/" + empty.getId()).with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("CUSTOMER_DELETE"))
                .andExpect(jsonPath("$.exposure").value(0.00))
                .andExpect(jsonPath("$.thresholdApplied").value(0.00))
                .andReturn();

        // No threshold is configured anywhere, and it is held anyway: the cascade cannot be
        // undone, so the emptiest account — the one most likely to be deleted by mistake — is the
        // one a threshold would wave through (CP-04, B2).
        assertThat(body(result))
                .contains("always needs a second pair of eyes");
        assertThat(customerRepository.existsById(empty.getId())).isTrue();

        PendingChange held = pendingChangeRepository
                .findById(jsonLong(result, "pendingChangeId")).orElseThrow();
        assertThat(held.isAlwaysChecked()).isTrue();
        assertThat(held.getPendingKey()).isEqualTo("CUSTOMER:" + empty.getId());
        assertThat(held.getBeforeJson()).contains("Nothing Owed Ltd");
    }

    // ----------------------------------------------------------------- where the limit comes from

    @Test
    void raisingTheRegionThresholdLetsTheSamePaymentThroughWithoutApproval() throws Exception {
        threshold(defaultRegion().getId(), LAKH, true);
        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isAccepted());
        assertThat(paymentRepository.count()).isZero();

        threshold(defaultRegion().getId(), "2000000.00", true);

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isOk());
        assertThat(paymentRepository.count()).isEqualTo(1);
        // And the change raised under the old limit still remembers the limit it was measured
        // against, so raising the bar neither auto-approves it nor re-holds what went through (B2).
        assertThat(pendingChangeRepository.findAll()).singleElement()
                .extracting(PendingChange::getThresholdApplied)
                .isEqualTo(new BigDecimal("100000.00"));
    }

    @Test
    void aRegionWithMakerCheckerSwitchedOffHoldsNothing() throws Exception {
        // Switched off is not the same as set very high: the row is there and its amount would
        // have held this payment twelve times over (B2).
        threshold(defaultRegion().getId(), LAKH, false);

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isOk());

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void aDeploymentThatConfiguresNoDefaultThresholdHoldsNothingInARegionWithNoRow() throws Exception {
        // The shipped posture: dormant. app.approvals.default-threshold binds empty to null, and
        // a region with no row of its own then holds nothing at all (B2).
        assertThat(approvalProperties.defaultThreshold()).isNull();
        assertThat(approvalThresholdRepository.findByRegionId(defaultRegion().getId())).isEmpty();

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isOk());

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.count()).isZero();
    }

    @Test
    void twoRegionsWithDifferentThresholdsHoldAndPassTheSameAmountDifferently() throws Exception {
        Region west = region("WEST");
        Customer western = customerRepository.save(
                Customer.builder().name("Western Mills").region(west).build());
        threshold(defaultRegion().getId(), LAKH, true);
        threshold(west.getId(), "5000000.00", true);

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payment(TWELVE_FIFTY))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.regionId").value(defaultRegion().getId()));

        mockMvc.perform(post("/api/payments").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PaymentDtos.CreatePaymentRequest(western.getId(),
                                new BigDecimal(TWELVE_FIFTY), "NEFT", null, List.of(),
                                admin.getId(), null))))
                .andExpect(status().isOk());

        // One amount, two answers, and the difference is the branch the ACCOUNT is filed in —
        // never the branch the person happens to be working from (B2, B1).
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.findAll()).singleElement()
                .extracting(PendingChange::getRegionId).isEqualTo(defaultRegion().getId());
    }

    // --------------------------------------------------------------- one waiting change per record

    @Test
    void aSecondChangeOnARecordThatAlreadyHasOneIsRefusedWithTheIdOfTheOneThatIsThere() throws Exception {
        Invoice big = invoice(20_000);
        threshold(defaultRegion().getId(), LAKH, true);

        MvcResult first = mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long waiting = jsonLong(first, "pendingChangeId");

        mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "A change on this record is already waiting for approval (change #" + waiting + ")"));

        // One row, not two, and the record is still spoken for by the first one (B2).
        assertThat(pendingChangeRepository.count()).isEqualTo(1);
        assertThat(pendingChangeRepository.findByPendingKey("INVOICE:" + big.getId()))
                .get().extracting(PendingChange::getId).isEqualTo(waiting);
    }

    @Test
    void theSentenceForTheLoserOfARaceTwoMakersBothPassedNamesTheChangeThatIsWaiting() throws Exception {
        Invoice big = invoice(20_000);
        threshold(defaultRegion().getId(), LAKH, true);
        MvcResult first = mockMvc.perform(post("/api/invoices/" + big.getId() + "/cancel").with(as(admin)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long waiting = jsonLong(first, "pendingChangeId");

        // The loser of that race never reaches the gate's exists(): its own transaction rolled
        // back and released the invoice's row lock before the winner's row was written, so
        // uq_pending_open is the only thing that can decide between them. This is the sentence
        // GlobalExceptionHandler then answers 409 with (B2).
        ApprovalDtos.Accepted told = approvalService.describeExisting(
                PendingChange.builder()
                        .action(PendingAction.INVOICE_CANCEL)
                        .targetType(PendingTargetType.INVOICE)
                        .targetId(big.getId())
                        .customerId(acme.getId())
                        .regionId(defaultRegion().getId())
                        .exposure(new BigDecimal("2000000.00"))
                        .thresholdApplied(new BigDecimal(LAKH))
                        .payloadJson("{}")
                        .summary("Cancel invoice " + big.getInvoiceNumber())
                        .status(PendingChangeStatus.PENDING)
                        .build(),
                "/api/invoices/" + big.getId() + "/cancel");

        assertThat(told.pendingChangeId()).isEqualTo(waiting);
        assertThat(told.message()).isEqualTo(
                "A change on this record is already waiting for approval (change #" + waiting + ")");
    }

    // ------------------------------------------------------------------------------- fixtures

    private void threshold(Long regionId, String amount, boolean enabled) {
        ApprovalThreshold row = approvalThresholdRepository.findByRegionId(regionId)
                .orElseGet(() -> ApprovalThreshold.builder().regionId(regionId).build());
        row.setAmount(new BigDecimal(amount));
        row.setEnabled(enabled);
        approvalThresholdRepository.saveAndFlush(row);
    }

    private PaymentDtos.CreatePaymentRequest payment(String amount) {
        return payment(amount, List.of());
    }

    private PaymentDtos.CreatePaymentRequest payment(String amount, List<Long> invoiceIds) {
        return new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal(amount), "NEFT",
                null, invoiceIds, collections.getId(), null);
    }

    private InvoiceDtos.CreateInvoiceRequest invoiceRequest(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00"))));
    }

    private Invoice invoice(int quantity) {
        actAs(admin);
        return invoiceService.create(invoiceRequest(quantity));
    }

    private BigDecimal creditBalance() {
        return customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance();
    }

    /** The running number off "INV-20260923-0007", so a held create can be shown to have burned
     *  nothing between two that did (B2). */
    private static int suffix(String invoiceNumber) {
        return Integer.parseInt(invoiceNumber.substring(invoiceNumber.lastIndexOf('-') + 1));
    }

    private Long jsonLong(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(body(result)).get(field).asLong();
    }

    /** Read as UTF-8 and not through the response's own encoding, or every rupee sign in a
     *  message an operator will actually read comes back as mojibake (B2). */
    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
