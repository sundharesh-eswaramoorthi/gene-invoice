package com.geneinvoice.dispute;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
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

class DisputeApprovalRaceTest extends IntegrationTestBase {

    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PlatformTransactionManager transactionManager;

    private static final long HOLD_MS = 300;

    User admin;
    User seller;
    User collector;
    Customer acme;
    User acmeLogin;
    Payment payment;
    Dispute dispute;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        seller = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        acmeLogin = customerUser("acme.login", acme.getId());
        Product widget = product("Widget", "500.00");
        actAs(admin);
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                acme.getId(), null, null, seller.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("500.00")))));
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Cash", null, List.of(invoice.getId()),
                collector.getId(), null));
        actAs(acmeLogin);
        dispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(
                DisputeTargetType.PAYMENT, payment.getId(), "We paid 300, not 500",
                "{\"action\":\"update_amount\",\"amount\":300}"));
        actAs(admin);
    }

    @Test
    void theSecondApprovalIsToldTheDisputeIsAlreadyResolved() throws Exception {
        AtomicReference<Throwable> second = race(
                () -> disputeService.approve(dispute.getId(),
                        new DisputeDtos.ResolveDisputeRequest("ok", null)),
                () -> disputeService.approve(dispute.getId(),
                        new DisputeDtos.ResolveDisputeRequest("ok again", null)));

        assertThat(second.get())
                .as("the loser is told what happened, not handed a server error")
                .isNotNull()
                .hasMessageContaining("Dispute already resolved");

        assertThat(disputeRepository.findById(dispute.getId()).orElseThrow().getStatus())
                .isEqualTo(DisputeStatus.APPROVED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("300.00");
    }

    @Test
    void anApprovalRacingADenialLeavesOneOutcomeAndOneMessage() throws Exception {
        AtomicReference<Throwable> second = race(
                () -> disputeService.approve(dispute.getId(),
                        new DisputeDtos.ResolveDisputeRequest("ok", null)),
                () -> disputeService.deny(dispute.getId(),
                        new DisputeDtos.ResolveDisputeRequest("no", null)));

        assertThat(second.get()).isNotNull().hasMessageContaining("Dispute already resolved");
        assertThat(disputeRepository.findById(dispute.getId()).orElseThrow().getStatus())
                .isEqualTo(DisputeStatus.APPROVED);
    }

    @Test
    void aSecondApprovalOnItsOwnSaysTheSameThing() {
        disputeService.approve(dispute.getId(), new DisputeDtos.ResolveDisputeRequest("ok", null));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                disputeService.approve(dispute.getId(),
                        new DisputeDtos.ResolveDisputeRequest("again", null))))
                .hasMessageContaining("Dispute already resolved");
    }

    private AtomicReference<Throwable> race(Runnable first, Runnable second) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    first.run();
                    firstIsInFlight.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {
                firstIsInFlight.countDown();
                firstFailure.set(t);
            }
        }, "resolve-one");

        Thread two = new Thread(() -> {
            actAs(admin);
            try {
                firstIsInFlight.await(5, TimeUnit.SECONDS);
                second.run();
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "resolve-two");

        one.start();
        two.start();
        one.join(30_000);
        two.join(30_000);
        assertThat(firstFailure.get()).isNull();
        return secondFailure;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
