package com.geneinvoice.promise;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Feature B: promise status is recomputed from facts, so it survives voids, edits and cancellations. */
class PromiseLifecycleTest extends IntegrationTestBase {

    @Autowired PaymentPromiseService promiseService;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PocService pocService;

    User admin;
    User collections;
    Customer acme;
    Product widget;

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final LocalDate TOMORROW = TODAY.plusDays(1);
    static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    private Invoice invoice(String unitPrice, int qty) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), qty, new BigDecimal(unitPrice)))));
    }

    private Payment pay(String amount, List<Long> invoiceIds) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal(amount), "Cash", null, invoiceIds,
                collections.getId(), null));
    }

    private PromiseDtos.PromiseDto promise(String amount, LocalDate date, List<Long> invoiceIds) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal(amount), date, null, "note", invoiceIds));
    }

    private PromiseStatus statusOf(Long id) {
        return promiseRepository.findById(id).orElseThrow().getStatus();
    }

    // ---- PPD-02: a promise kept is kept, whatever the date says ----------------

    /**
     * A promise of part of a larger invoice, paid in full and on time, is kept from the moment the
     * money lands. It used to read "Partially kept" with nothing left to pay until the promised
     * date went by, and then turn into "Kept" on the same facts (PPD-02).
     */
    @Test
    void anInvoiceScopedPromisePaidInFullBeforeItsDateIsKept() {
        Invoice big = invoice("1000.00", 1);
        PromiseDtos.PromiseDto part = promise("200", TOMORROW, List.of(big.getId()));

        pay("200.00", List.of(big.getId()));

        PromiseDtos.PromiseDto kept = promiseService.dto(part.id());
        assertThat(kept.status()).isEqualTo(PromiseStatus.KEPT);
        assertThat(kept.fulfilledAmount()).isEqualByComparingTo("200.00");
        assertThat(kept.remainingAmount()).isEqualByComparingTo("0.00");
    }

    /** And it is still kept once the date has gone: the same facts cannot mean two things. */
    @Test
    void thatPromiseIsStillKeptOnceThePromisedDateHasPassed() {
        Invoice big = invoice("1000.00", 1);
        PromiseDtos.PromiseDto part = promise("200", TOMORROW, List.of(big.getId()));
        pay("200.00", List.of(big.getId()));

        PaymentPromise stored = promiseRepository.findById(part.id()).orElseThrow();
        stored.setPromisedDate(YESTERDAY);
        promiseRepository.saveAndFlush(stored);
        promiseService.sweepOverdue();

        assertThat(statusOf(part.id())).isEqualTo(PromiseStatus.KEPT);
    }

    /** Short of the promise, it is still only partly kept — the fix is not "everything is kept". */
    @Test
    void anInvoiceScopedPromisePaidShortBeforeItsDateIsOnlyPartlyKept() {
        Invoice big = invoice("1000.00", 1);
        PromiseDtos.PromiseDto part = promise("200", TOMORROW, List.of(big.getId()));

        pay("150.00", List.of(big.getId()));

        assertThat(statusOf(part.id())).isEqualTo(PromiseStatus.PARTIALLY_KEPT);
    }

    // ---- AC-B1 / AC-B2: validation ---------------------------------------------

    @Test
    void amountMustBeGreaterThanZero() {
        assertThatThrownBy(() -> promise("0", TOMORROW, null))
                .hasMessageContaining("greater than zero");
    }

    @Test
    void aPromiseCannotReferenceAnotherCustomersInvoice() {
        Customer other = customer("Other Ltd");
        Invoice theirs = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                other.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        assertThatThrownBy(() -> promise("100", TOMORROW, List.of(theirs.getId())))
                .hasMessageContaining("belongs to a different customer");
    }

    @Test
    void aPromiseCannotReferenceACancelledInvoice() {
        Invoice inv = invoice("100.00", 1);
        invoiceService.cancel(inv.getId());
        assertThatThrownBy(() -> promise("100", TOMORROW, List.of(inv.getId())))
                .hasMessageContaining("cancelled");
    }

    @Test
    void thePromisedAmountMayDifferFromTheInvoiceBalanceWithoutBeingBlocked() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto under = promise("40.00", TOMORROW, List.of(inv.getId()));
        assertThat(under.status()).isEqualTo(PromiseStatus.OPEN);

        Invoice another = invoice("100.00", 1);
        PromiseDtos.PromiseDto over = promise("500.00", TOMORROW, List.of(another.getId()));
        assertThat(over.status()).isEqualTo(PromiseStatus.OPEN);
    }

    // ---- AC-B8: a Collection POC is required, defaulting to the customer's primary

    @Test
    void creationDefaultsToTheCustomersPrimaryCollectionPoc() {
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, null);
        assertThat(p.collectionPoc().id()).isEqualTo(collections.getId());
    }

    @Test
    void creationFailsWhenNoCollectionPocIsGivenOrDerivable() {
        Customer bare = customer("Bare Ltd");
        assertThatThrownBy(() -> promiseService.create(new PromiseDtos.CreatePromiseRequest(
                bare.getId(), new BigDecimal("10"), TOMORROW, null, null, null)))
                .hasMessageContaining("Collection POC is required");
    }

    // ---- AC-B3: a settling payment links itself and marks KEPT -------------------

    @Test
    void aPaymentThatFullySettlesThePromisedInvoicesMarksItKeptWithoutHumanAction() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));

        pay("100.00", List.of(inv.getId()));

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("100.00");
    }

    // ---- AC-B4: a partial payment marks PARTIALLY_KEPT and records the remainder --

    @Test
    void aPartialPaymentMarksPartiallyKeptAndRecordsWhatIsLeft() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));

        pay("40.00", List.of(inv.getId()));

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.PARTIALLY_KEPT);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("40.00");
        assertThat(reloaded.getRemainingAmount()).isEqualByComparingTo("60.00");
    }

    // ---- AC-B5: the date passing turns an untouched promise BROKEN by itself -----

    @Test
    void theSweepBreaksAnOverduePromiseNobodyEverOpened() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, List.of(inv.getId()));
        assertThat(p.status()).isEqualTo(PromiseStatus.BROKEN); // evaluated on create too

        // Force it back to OPEN as if it had been created before the date passed.
        PaymentPromise stored = promiseRepository.findById(p.id()).orElseThrow();
        stored.setStatus(PromiseStatus.OPEN);
        stored.setBrokenNotifiedAt(null);
        promiseRepository.save(stored);

        promiseService.sweepOverdue();

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.BROKEN);
    }

    @Test
    void theSweepLeavesAPromiseThatIsNotYetDueAlone() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        promiseService.sweepOverdue();
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.OPEN);
    }

    @Test
    void theSweepIsIdempotent() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, List.of(inv.getId()));
        promiseService.sweepOverdue();
        int second = promiseService.sweepOverdue();
        assertThat(second).isZero();
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.BROKEN);
    }

    @Test
    void payingAfterTheDateDoesNotUnbreakThePromise() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, List.of(inv.getId()));
        assertThat(p.status()).isEqualTo(PromiseStatus.BROKEN);

        pay("100.00", List.of(inv.getId()));

        // The customer did break their word; the late money is still recorded against it.
        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.BROKEN);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void aCustomerLevelPromisePaidLateAlsoStaysBroken() {
        invoice("300.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, null);
        assertThat(p.status()).isEqualTo(PromiseStatus.BROKEN);

        pay("100.00", null);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.BROKEN);
    }

    @Test
    void aPromiseSettledOnTheDayItselfIsKept() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TODAY, List.of(inv.getId()));
        pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void payingLessThanPromisedButSettlingTheInvoicesInTimeStillCountsAsKept() {
        Invoice inv = invoice("100.00", 1);
        // They promised more than the invoices actually owe (AC-B2).
        PromiseDtos.PromiseDto p = promise("500.00", TODAY, List.of(inv.getId()));
        pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    // ---- AC-B10: the Collection POC is told once, not every sweep ----------------

    @Test
    void breakingNotifiesTheCollectionPocExactlyOnce() {
        Invoice inv = invoice("100.00", 1);
        promise("100.00", YESTERDAY, List.of(inv.getId()));
        promiseService.sweepOverdue();
        promiseService.sweepOverdue();

        List<com.geneinvoice.notification.Notification> broken =
                notificationRepository.findByUserIdOrderByCreatedAtDesc(collections.getId()).stream()
                        .filter(n -> n.getType().equals("PROMISE_BROKEN")).toList();
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).getLink()).startsWith("/promises/");
    }

    // ---- AC-B6: re-entrancy — a void, a cancel and an edit all re-evaluate -------

    @Test
    void voidingTheLinkedPaymentTakesTheKeptStatusBackAgain() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        Payment payment = pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);

        paymentService.voidPayment(payment.getId());

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.OPEN);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("0.00");
        assertThat(promiseRepository.countLinkedPayments(p.id())).isZero();
    }

    @Test
    void aDisputeDrivenInvoiceCancellationLeavesNoStaleBrokenStatus() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.BROKEN);

        invoiceService.cancelWithRefund(inv.getId());

        // Nothing is owed on what was promised, so the promise is no longer broken.
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void reducingAnInvoiceThroughADisputeEditKeepsThePromiseHonest() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        pay("40.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.PARTIALLY_KEPT);

        // The dispute reduces the invoice to what was already paid.
        invoiceService.replaceItems(inv.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("40.00"))), null);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void changingAPaymentsAmountReEvaluatesThePromise() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        Payment payment = pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);

        paymentService.updateAmount(payment.getId(), new BigDecimal("30.00"), null, null);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.PARTIALLY_KEPT);
    }

    @Test
    void evaluationIsIdempotentWhenNothingHasChanged() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        pay("100.00", List.of(inv.getId()));

        promiseService.reevaluateForCustomer(acme.getId());
        promiseService.reevaluateForCustomer(acme.getId());

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("100.00");
    }

    // ---- AC-B7: a customer-level promise tracks the account balance --------------

    @Test
    void aCustomerLevelPromiseIsKeptOnceThePromisedAmountIsPaid() {
        invoice("300.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, null);

        pay("100.00", null);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void aCustomerLevelPromiseBreaksWhenTheDatePassesWithNothingPaid() {
        invoice("300.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", YESTERDAY, null);
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.BROKEN);
    }

    @Test
    void aCustomerLevelPromiseIsKeptWhenTheAccountOwesNothingByTheDate() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("500.00", TODAY, null);
        pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    // ---- AC-B6 / AC-B9: manual override wins and is audited ---------------------

    @Test
    void aManualOverridePinsTheStatusAgainstFurtherRecomputation() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));

        promiseService.override(p.id(), PromiseStatus.KEPT, "Paid in cash off-system");
        pay("10.00", List.of(inv.getId()));

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(reloaded.isStatusOverridden()).isTrue();
        assertThat(reloaded.getOverrideReason()).isEqualTo("Paid in cash off-system");
        assertThat(reloaded.getOverriddenByUserId()).isEqualTo(admin.getId());
        // Fulfilment is still tracked underneath the override.
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void anOverrideRequiresAReason() {
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, null);
        assertThatThrownBy(() -> promiseService.override(p.id(), PromiseStatus.KEPT, "  "))
                .hasMessageContaining("requires a reason");
    }

    @Test
    void clearingAnOverrideHandsThePromiseBackToAutomaticTracking() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        promiseService.override(p.id(), PromiseStatus.KEPT, "off-system");

        promiseService.clearOverride(p.id());

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.OPEN);
    }

    // ---- AC-B11: cancelling unlinks payments without touching them ---------------

    @Test
    void cancellingAPromiseUnlinksItsPaymentsWithoutAlteringThem() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        Payment payment = pay("100.00", List.of(inv.getId()));

        promiseService.cancel(p.id(), "raised in error");

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        assertThat(promiseRepository.countLinkedPayments(p.id())).isZero();

        Payment untouched = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(com.geneinvoice.payment.PaymentStatus.ACTIVE);
        assertThat(untouched.getAmount()).isEqualByComparingTo("100.00");
        assertThat(invoiceRepository.findById(inv.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void aCancelledPromiseIsNeverResurrectedByEvaluation() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        promiseService.cancel(p.id(), null);

        pay("100.00", List.of(inv.getId()));
        promiseService.sweepOverdue();

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.CANCELLED);
    }

    // ---- AC-B12: many-to-many between payments and promises ---------------------

    @Test
    void onePaymentMayFulfilSeveralPromisesWithoutDoubleCounting() {
        Invoice a = invoice("100.00", 1);
        Invoice b = invoice("100.00", 1);
        PromiseDtos.PromiseDto first = promise("100.00", TOMORROW, List.of(a.getId()));
        PromiseDtos.PromiseDto second = promise("100.00", TOMORROW, List.of(b.getId()));

        pay("200.00", List.of(a.getId(), b.getId()));

        PaymentPromise reloadedFirst = promiseRepository.findById(first.id()).orElseThrow();
        PaymentPromise reloadedSecond = promiseRepository.findById(second.id()).orElseThrow();
        assertThat(reloadedFirst.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(reloadedSecond.getStatus()).isEqualTo(PromiseStatus.KEPT);
        // Each promise counts only the part of the payment that landed on its own invoice.
        assertThat(reloadedFirst.getFulfilledAmount()).isEqualByComparingTo("100.00");
        assertThat(reloadedSecond.getFulfilledAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void severalPaymentsMayTogetherFulfilOnePromise() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));

        pay("60.00", List.of(inv.getId()));
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.PARTIALLY_KEPT);

        pay("40.00", List.of(inv.getId()));

        PaymentPromise reloaded = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(reloaded.getFulfilledAmount()).isEqualByComparingTo("100.00");
        assertThat(promiseRepository.countLinkedPayments(p.id())).isEqualTo(2);
    }

    // ---- US-B3: a cashier may link a payment to a promise by hand ---------------

    @Test
    void aCashierCanLinkAPaymentToAnOpenPromiseExplicitly() {
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, null);
        paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("100.00"), "Cash", null, null,
                collections.getId(), List.of(p.id())));

        assertThat(promiseRepository.countLinkedPayments(p.id())).isEqualTo(1);
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    // ---- AC-B7: a general promise answers only for the debt it was made against --

    @Test
    void aKeptGeneralPromiseStaysKeptWhenALaterInvoiceIsRaised() {
        PromiseDtos.PromiseDto p = promise("500.00", TODAY.minusDays(2), null);
        assertThat(p.status()).isEqualTo(PromiseStatus.KEPT);

        invoice("300.00", 1);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(collections.getId()))
                .noneMatch(n -> n.getType().equals("PROMISE_BROKEN"));
    }

    @Test
    void anInvoiceDatedByThePromisedDayStillCountsAgainstAGeneralPromise() {
        PromiseDtos.PromiseDto p = promise("100.00", TODAY, null);
        assertThat(p.status()).isEqualTo(PromiseStatus.KEPT);

        invoice("300.00", 1);

        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.OPEN);
    }

    // ---- editing after the facts have moved on ------------------------------------

    @Test
    void aPromiseStaysEditableAfterOneOfItsInvoicesIsCancelled() {
        Invoice a = invoice("100.00", 1);
        Invoice b = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("200.00", TOMORROW, List.of(a.getId(), b.getId()));
        invoiceService.cancel(b.getId());

        PromiseDtos.PromiseDto edited = promiseService.update(p.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("200.00"), TOMORROW, null, "edited", List.of(a.getId(), b.getId())));
        assertThat(edited.notes()).isEqualTo("edited");

        PromiseDtos.PromiseDto unticked = promiseService.update(p.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("100.00"), TOMORROW, null, "edited", List.of(a.getId())));
        assertThat(unticked.invoices()).extracting(PromiseDtos.PromiseInvoiceDto::id)
                .containsExactly(a.getId());
    }

    @Test
    void aCancelledInvoiceStillCannotBeNewlyAddedToAPromise() {
        Invoice a = invoice("100.00", 1);
        Invoice c = invoice("100.00", 1);
        invoiceService.cancel(c.getId());
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(a.getId()));

        assertThatThrownBy(() -> promiseService.update(p.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("100.00"), TOMORROW, null, "n", List.of(a.getId(), c.getId()))))
                .hasMessageContaining("cancelled");
    }

    @Test
    void aPromiseWhoseCollectionPocWasDeactivatedCanStillBeEdited() {
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, null);
        collections.setActive(false);
        userRepository.save(collections);

        PromiseDtos.PromiseDto edited = promiseService.update(p.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("100.00"), TOMORROW, collections.getId(), "still theirs", null));
        assertThat(edited.notes()).isEqualTo("still theirs");
    }

    // ---- a withdrawn promise stays withdrawn; defaults skip deactivated POCs ----------

    @Test
    void aCancelledPromiseCannotBeOverriddenOrHaveAnOverrideCleared() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(inv.getId()));
        promiseService.override(p.id(), PromiseStatus.KEPT, "agreed by phone");
        promiseService.cancel(p.id(), "raised in error");

        assertThatThrownBy(() -> promiseService.override(p.id(), PromiseStatus.OPEN, "revive"))
                .hasMessageContaining("cancelled");
        assertThatThrownBy(() -> promiseService.clearOverride(p.id()))
                .hasMessageContaining("cancelled");

        pay("100.00", List.of(inv.getId()));
        PaymentPromise after = promiseRepository.findById(p.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PromiseStatus.CANCELLED);
        assertThat(after.isStatusOverridden()).isFalse();
        assertThat(promiseRepository.countLinkedPayments(p.id())).isZero();
    }

    @Test
    void aDeactivatedPrimaryIsSkippedForTheNextActiveCollectionPoc() {
        User cole = user("cole.collections", DataSeeder.ROLE_COLLECTION_POC);
        pocService.add(acme.getId(), PocType.COLLECTION, cole.getId(), false);
        collections.setActive(false);
        userRepository.save(collections);

        PromiseDtos.PromiseDto p = promise("50.00", TOMORROW, null);
        assertThat(p.collectionPoc().id()).isEqualTo(cole.getId());
    }

    // ---- AC-B12: one payment is shared out once across the promises it could serve ------

    private BigDecimal fulfilledOf(Long id) {
        return promiseRepository.findById(id).orElseThrow().getFulfilledAmount();
    }

    private BigDecimal fulfilledTotal() {
        return promiseRepository.findAll().stream().map(PaymentPromise::getFulfilledAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void twoPromisesOnOneInvoiceCountOnePaymentOnce() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto first = promise("100.00", TOMORROW, List.of(inv.getId()));
        PromiseDtos.PromiseDto second = promise("100.00", TOMORROW.plusDays(1), List.of(inv.getId()));

        pay("100.00", List.of(inv.getId()));

        // Both are kept, since the invoice they cover is settled (AC-B3), but the money counts once.
        assertThat(statusOf(first.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(statusOf(second.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(fulfilledOf(first.id())).isEqualByComparingTo("100.00");
        assertThat(fulfilledOf(second.id())).isEqualByComparingTo("0");
        assertThat(fulfilledTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void twoGeneralPromisesShareOnePaymentEarliestDateFirst() {
        invoice("300.00", 1);
        PromiseDtos.PromiseDto later = promise("100.00", TOMORROW.plusDays(2), null);
        PromiseDtos.PromiseDto earlier = promise("100.00", TOMORROW, null);

        pay("100.00", null);

        assertThat(statusOf(earlier.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(statusOf(later.id())).isEqualTo(PromiseStatus.OPEN);
        assertThat(fulfilledTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void moneyOnAPromisedInvoiceGoesToThatPromiseNotAnUnrelatedGeneralOne() {
        Invoice promised = invoice("250.00", 1);
        invoice("75.00", 1);
        PromiseDtos.PromiseDto scoped = promise("250.00", TOMORROW, List.of(promised.getId()));
        PromiseDtos.PromiseDto general = promise("75.00", TOMORROW, null);

        pay("250.00", List.of(promised.getId()));

        assertThat(statusOf(scoped.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(fulfilledOf(scoped.id())).isEqualByComparingTo("250.00");
        assertThat(statusOf(general.id())).isEqualTo(PromiseStatus.OPEN);
        assertThat(fulfilledOf(general.id())).isEqualByComparingTo("0");
    }

    @Test
    void lateMoneyGoesToAPromiseStillInTimeAndTheMissedOneStaysBroken() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto missed = promise("100.00", YESTERDAY, List.of(inv.getId()));
        PromiseDtos.PromiseDto current = promise("100.00", TOMORROW, List.of(inv.getId()));

        pay("100.00", List.of(inv.getId()));

        // The money cannot keep yesterday's promise, so it counts for the one it can still keep.
        assertThat(statusOf(current.id())).isEqualTo(PromiseStatus.KEPT);
        assertThat(fulfilledOf(current.id())).isEqualByComparingTo("100.00");
        // The invoice is settled, but only after yesterday: that promise was still broken.
        assertThat(statusOf(missed.id())).isEqualTo(PromiseStatus.BROKEN);
        assertThat(fulfilledOf(missed.id())).isEqualByComparingTo("0");
    }

    @Test
    void cancellingAPromiseFreesItsShareForTheNextOne() {
        Invoice inv = invoice("100.00", 1);
        PromiseDtos.PromiseDto first = promise("100.00", TOMORROW, null);
        PromiseDtos.PromiseDto second = promise("100.00", TOMORROW.plusDays(1), null);
        pay("100.00", List.of(inv.getId()));
        assertThat(statusOf(second.id())).isNotEqualTo(PromiseStatus.KEPT);

        promiseService.cancel(first.id(), "raised twice");

        assertThat(fulfilledOf(second.id())).isEqualByComparingTo("100.00");
    }

    // ---- D-35: a ticked promise gets the money ------------------------------------------

    @Test
    void aPaymentTickedToAPromisePaysThatPromisesInvoicesFirst() {
        Invoice older = invoice("100.00", 1);
        Invoice promised = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(promised.getId()));

        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("100.00"),
                "Cash", null, null, collections.getId(), List.of(p.id())));

        assertThat(invoiceRepository.findById(promised.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("100.00");
        assertThat(invoiceRepository.findById(older.getId()).orElseThrow().getPaidAmount())
                .isEqualByComparingTo("0");
        assertThat(statusOf(p.id())).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void aTickedPromiseTheChosenInvoicesCannotServeIsRefused() {
        Invoice older = invoice("100.00", 1);
        Invoice promised = invoice("100.00", 1);
        PromiseDtos.PromiseDto p = promise("100.00", TOMORROW, List.of(promised.getId()));

        assertThatThrownBy(() -> paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("100.00"), "Cash", null, List.of(older.getId()), collections.getId(),
                List.of(p.id()))))
                .hasMessageContaining("covers none of the chosen invoices");
    }

    // ---- recomputing existing promises under the new rules --------------------------------

    @Test
    void aRecomputePreviewReportsChangesAndOnlyApplyingKeepsThem() {
        Invoice inv = invoice("100.00", 1);
        promise("100.00", TOMORROW, List.of(inv.getId()));
        PromiseDtos.PromiseDto second = promise("100.00", TOMORROW.plusDays(1), List.of(inv.getId()));
        pay("100.00", List.of(inv.getId()));
        // As the old rule left it: the one payment counted in full by both promises.
        PaymentPromise stored = promiseRepository.findById(second.id()).orElseThrow();
        stored.setFulfilledAmount(new BigDecimal("100.00"));
        promiseRepository.save(stored);

        List<PaymentPromiseService.RecomputeChange> preview = promiseService.recomputeAll(false);
        assertThat(preview).extracting(PaymentPromiseService.RecomputeChange::promiseId).contains(second.id());
        assertThat(fulfilledOf(second.id())).isEqualByComparingTo("100.00");

        promiseService.recomputeAll(true);
        assertThat(fulfilledOf(second.id())).isEqualByComparingTo("0");
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(collections.getId()))
                .noneMatch(n -> n.getType().equals("PROMISE_BROKEN"));
    }

    @Test
    void withNoActiveCollectionPocAPromiseAsksForOne() {
        collections.setActive(false);
        userRepository.save(collections);

        assertThatThrownBy(() -> promise("50.00", TOMORROW, null))
                .hasMessageContaining("no active Collection POC");
    }
}
