package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationRepository;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money oracle. Each of the three writes maker-checker will later hold for approval — record a
 * payment, create an invoice, cancel an invoice — is pinned here field by field, against the code
 * as it stands BEFORE any gate exists. A held-then-approved write must land these same figures, so
 * this class is the control: it is written once and then left alone, and a later unit that has to
 * edit an expected number here has changed what the money does, not merely when it is written
 * (B2).
 */
class MoneyOracleTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PocService pocService;
    @Autowired PaymentAllocationRepository allocationRepository;

    static final LocalDate TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1);

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
        pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true);
    }

    @Test
    void recordingAPaymentLandsTheseExactFigures() {
        Invoice older = invoice(10, daysAgo(3));
        Invoice newer = invoice(5, daysAgo(2));
        PromiseDtos.PromiseDto promised = promise("1000.00", List.of(older.getId()));

        Payment payment = pay("1700.00", List.of(older.getId(), newer.getId()));

        assertInvoice(older, "1000.00", "1000.00", InvoiceStatus.FULLY_PAID);
        assertInvoice(newer, "500.00", "500.00", InvoiceStatus.FULLY_PAID);
        assertThat(allocations()).containsOnlyKeys(older.getId(), newer.getId());
        assertThat(allocations().get(older.getId())).isEqualByComparingTo("1000.00");
        assertThat(allocations().get(newer.getId())).isEqualByComparingTo("500.00");
        assertPayment(payment, "1700.00", "200.00", PaymentStatus.ACTIVE);
        assertThat(creditBalance()).isEqualByComparingTo("200.00");
        assertPromise(promised, "1000.00", PromiseStatus.KEPT);
    }

    @Test
    void creatingAnInvoiceLandsTheseExactFigures() {
        PromiseDtos.PromiseDto promised = promise("500.00", List.of());
        Payment onAccount = pay("300.00", List.of());
        assertThat(creditBalance()).isEqualByComparingTo("300.00");

        Invoice raised = invoice(5, daysAgo(1));

        assertInvoice(raised, "500.00", "300.00", InvoiceStatus.PARTIALLY_PAID);
        assertThat(allocations()).containsOnlyKeys(raised.getId());
        assertThat(allocations().get(raised.getId())).isEqualByComparingTo("300.00");
        assertPayment(onAccount, "300.00", "0.00", PaymentStatus.ACTIVE);
        assertThat(creditBalance()).isEqualByComparingTo("0.00");
        assertPromise(promised, "300.00", PromiseStatus.PARTIALLY_KEPT);
    }

    @Test
    void cancellingAnInvoiceLandsTheseExactFigures() {
        Invoice paid = invoice(10, daysAgo(3));
        Invoice empty = invoice(5, daysAgo(2));
        PromiseDtos.PromiseDto promised = promise("1000.00", List.of(paid.getId()));
        Payment payment = pay("400.00", List.of(paid.getId()));

        invoiceService.cancel(empty.getId());

        assertInvoice(empty, "500.00", "0.00", InvoiceStatus.CANCELLED);
        assertInvoice(paid, "1000.00", "400.00", InvoiceStatus.PARTIALLY_PAID);
        assertThat(allocations()).containsOnlyKeys(paid.getId());
        assertThat(allocations().get(paid.getId())).isEqualByComparingTo("400.00");
        assertPayment(payment, "400.00", "0.00", PaymentStatus.ACTIVE);
        assertThat(creditBalance()).isEqualByComparingTo("0.00");
        assertPromise(promised, "400.00", PromiseStatus.PARTIALLY_KEPT);
    }

    /**
     * The two rows that gained an optimistic-lock counter in this unit. Until now the second of two
     * concurrent edits overwrote the first in silence; the counter is what lets the loser be told
     * (B2).
     */
    @Test
    void aPaymentAndAPromiseEachCarryARowVersion() {
        Invoice raised = invoice(10, daysAgo(2));
        PromiseDtos.PromiseDto promised = promise("1000.00", List.of(raised.getId()));
        Payment payment = pay("400.00", List.of(raised.getId()));
        Long versionOnRecord = reload(payment).getVersion();

        paymentService.update(payment.getId(),
                new PaymentDtos.UpdatePaymentRequest("second thoughts", null));

        assertThat(versionOnRecord).isNotNull();
        assertThat(reload(promised).getVersion()).isNotNull();
        assertThat(reload(payment).getVersion()).isGreaterThan(versionOnRecord);
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private Invoice invoice(int quantity, Instant invoiceDate) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), invoiceDate, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00")))));
    }

    private PromiseDtos.PromiseDto promise(String amount, List<Long> invoiceIds) {
        return promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal(amount), TOMORROW, collections.getId(),
                "the oracle's promise", invoiceIds));
    }

    private Payment pay(String amount, List<Long> invoiceIds) {
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal(amount), "Cash", null, invoiceIds, collections.getId(), null));
    }

    private void assertInvoice(Invoice invoice, String total, String paid, InvoiceStatus status) {
        Invoice fresh = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(fresh.getTotal()).isEqualByComparingTo(total);
        assertThat(fresh.getPaidAmount()).isEqualByComparingTo(paid);
        assertThat(fresh.getBalance()).isEqualByComparingTo(new BigDecimal(total).subtract(new BigDecimal(paid)));
        assertThat(fresh.getStatus()).isEqualTo(status);
    }

    private void assertPayment(Payment payment, String amount, String creditApplied, PaymentStatus status) {
        Payment fresh = reload(payment);
        assertThat(fresh.getAmount()).isEqualByComparingTo(amount);
        assertThat(fresh.getCreditApplied()).isEqualByComparingTo(creditApplied);
        assertThat(fresh.getStatus()).isEqualTo(status);
    }

    private void assertPromise(PromiseDtos.PromiseDto promise, String fulfilled, PromiseStatus status) {
        PaymentPromise fresh = reload(promise);
        assertThat(fresh.getFulfilledAmount()).isEqualByComparingTo(fulfilled);
        assertThat(fresh.getStatus()).isEqualTo(status);
    }

    private Payment reload(Payment payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private PaymentPromise reload(PromiseDtos.PromiseDto promise) {
        return promiseRepository.findById(promise.id()).orElseThrow();
    }

    private BigDecimal creditBalance() {
        return customerRepository.findById(acme.getId()).orElseThrow().getCreditBalance();
    }

    /** Allocated money by invoice id, read back from the table rather than a lazy collection. */
    private Map<Long, BigDecimal> allocations() {
        return allocationRepository.findByCustomerIdWithPayment(acme.getId()).stream()
                .collect(Collectors.toMap(a -> a.getInvoice().getId(), PaymentAllocation::getAmount,
                        BigDecimal::add, LinkedHashMap::new));
    }
}
