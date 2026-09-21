package com.geneinvoice.payment;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentMoneyTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentAllocationRepository allocationRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private static final long HOLD_MS = 300;

    User admin;
    User seller;
    User collector;
    Customer customer;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        seller = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        customer = customer("Race Co");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    private PaymentDtos.CreatePaymentRequest payment(String amount, List<Long> invoiceIds) {
        return new PaymentDtos.CreatePaymentRequest(customer.getId(), new BigDecimal(amount),
                "CASH", null, invoiceIds, collector.getId(), null);
    }

    @Test
    void twoPaymentsSettlingOneInvoiceAtOnceKeepEveryPennyAccountedFor() throws Exception {
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                customer.getId(), null, null, seller.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("100.00")))));

        race(payment("100.00", List.of(invoice.getId())), payment("100.00", List.of(invoice.getId())));

        assertBooksBalance();
    }

    @Test
    void twoPaymentsWithNoInvoiceToLandOnBothReachTheCustomersCredit() throws Exception {
        race(payment("100.00", List.of()), payment("100.00", List.of()));

        assertBooksBalance();
        assertThat(customerRepository.findById(customer.getId()).orElseThrow().getCreditBalance())
                .isEqualByComparingTo(acceptedTotal());
    }

    @Test
    void aPaymentRecordedWhileAnEarlierOneIsStillOpenSeesWhatItPaid() throws Exception {
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                customer.getId(), null, null, seller.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 3, new BigDecimal("100.00")))));

        race(payment("100.00", List.of(invoice.getId())), payment("100.00", List.of(invoice.getId())));

        assertBooksBalance();
        Invoice settled = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(settled.getPaidAmount()).isEqualByComparingTo(acceptedTotal());
    }

    private void race(PaymentDtos.CreatePaymentRequest first,
                      PaymentDtos.CreatePaymentRequest second) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    paymentService.record(first);
                    firstIsInFlight.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {
                firstIsInFlight.countDown();
                firstFailure.set(t);
            }
        }, "payment-one");

        Thread two = new Thread(() -> {
            actAs(admin);
            try {
                firstIsInFlight.await(5, TimeUnit.SECONDS);
                paymentService.record(second);
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "payment-two");

        one.start();
        two.start();
        one.join(30_000);
        two.join(30_000);

        assertThat(firstFailure.get())
                .as("the payment that got there first must not be the one that is refused")
                .isNull();
        if (secondFailure.get() != null) {
            assertThat(paymentRepository.findAll()).hasSize(1);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertBooksBalance() {
        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).as("the payment that got there first was recorded").isNotEmpty();

        List<PaymentAllocation> allocations =
                allocationRepository.findByCustomerIdWithPayment(customer.getId());
        BigDecimal allocated = allocations.stream().map(PaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credited = payments.stream().map(Payment::getCreditApplied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(allocated.add(credited))
                .as("every payment taken is either standing against an invoice or sitting in credit")
                .isEqualByComparingTo(acceptedTotal());

        assertThat(customerRepository.findById(customer.getId()).orElseThrow().getCreditBalance())
                .as("the customer's credit is the credit its payments say they left there")
                .isEqualByComparingTo(credited);

        for (Invoice invoice : invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customer.getId())) {
            BigDecimal onThisInvoice = allocations.stream()
                    .filter(a -> a.getInvoice().getId().equals(invoice.getId()))
                    .map(PaymentAllocation::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(invoice.getPaidAmount())
                    .as("invoice %s agrees with the allocations standing against it", invoice.getId())
                    .isEqualByComparingTo(onThisInvoice);
            assertThat(invoice.getPaidAmount())
                    .as("no invoice is paid more than it is worth")
                    .isLessThanOrEqualTo(invoice.getTotal());
        }
    }

    private BigDecimal acceptedTotal() {
        return paymentRepository.findAll().stream().map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
