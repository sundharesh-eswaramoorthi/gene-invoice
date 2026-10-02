package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.ItemSnapshot;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writers of the same records at the same time still leave one history: at most one open version or link row per
 * record, and each record's intervals follow on from one another. Each thread signs in for itself; nothing
 * holds a lock for more than a moment, far inside H2's lock timeout.
 */
class HistoryConcurrencyTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    User admin;
    User sam;
    User cara;
    Product widget;
    Customer acme;
    ExecutorService threads;

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /**
     * Relinking touches only link rows, so neither transaction has locked the promise when both have written their
     * links: the writer locks it, and each versions the links committed before it and its own. Repeated, as the
     * window a missing lock leaves is only a few milliseconds wide.
     */
    @RepeatedTest(10)
    void twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink() throws Exception {
        Invoice kept = invoice();
        Invoice first = invoice();
        Invoice second = invoice();
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(kept.getId()))).id();
        CyclicBarrier bothWritten = new CyclicBarrier(2);

        List<Future<?>> running = List.of(
                threads.submit(relink(id, first.getId(), bothWritten)),
                threads.submit(relink(id, second.getId(), bothWritten)));
        for (Future<?> f : running) f.get(10, TimeUnit.SECONDS);

        List<EntityLinkVersion> links = historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICE, id);
        assertThat(links.stream().filter(VersionRow::isOpen).map(VersionRow::getId))
                .containsExactlyInAnyOrder(kept.getId(), first.getId(), second.getId());
        assertThat(links).hasSize(3);
        assertThat(links.stream().filter(l -> !l.getId().equals(kept.getId())).map(VersionRow::getTxId).distinct())
                .as("each link row is its own transaction's").hasSize(2);
        assertThat(links).allSatisfy(l -> assertThat(l.getActorUserId()).isEqualTo(admin.getId()));
        assertThat(historyTestSupport.versionsOf(PromiseVersion.class, id)).hasSize(1);
    }

    /**
     * The first writer is held after reading the promise's targets and before reading its open rows. A second
     * relink that could commit meanwhile would leave an open row the first never saw a target for, and the first
     * would close it; holding the promise, the first makes the second wait until it has committed.
     */
    @Test
    void aRelinkCommittingWhileAnotherIsVersioningThePromiseWaitsForIt() throws Exception {
        Invoice kept = invoice();
        Invoice first = invoice();
        Invoice second = invoice();
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(kept.getId()))).id();
        CountDownLatch firstBetweenReads = new CountDownLatch(1);
        CountDownLatch secondCommitted = new CountDownLatch(1);
        boolean[] secondCommittedMeanwhile = {false};
        historyFaults.onNextLinkReadsOf(LinkHistory.PROMISE_INVOICES.code(), id, () -> {
            firstBetweenReads.countDown();
            try {
                // Far inside H2's lock timeout (2 s), which the second spends waiting for the promise.
                secondCommittedMeanwhile[0] = secondCommitted.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        Future<?> firstRelink = threads.submit(() -> {
            actAs(admin);
            try {
                tx.executeWithoutResult(status -> {
                    PaymentPromise p = promiseRepository.findById(id).orElseThrow();
                    p.getInvoices().add(invoiceRepository.getReferenceById(first.getId()));
                    promiseRepository.saveAndFlush(p);
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        });
        Future<?> secondRelink = threads.submit(() -> {
            actAs(admin);
            try {
                tx.executeWithoutResult(status -> {
                    PaymentPromise p = promiseRepository.findById(id).orElseThrow();
                    p.getInvoices().add(invoiceRepository.getReferenceById(second.getId()));
                    promiseRepository.saveAndFlush(p);
                    await(firstBetweenReads);
                });
                secondCommitted.countDown();
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        });
        firstRelink.get(10, TimeUnit.SECONDS);
        secondRelink.get(10, TimeUnit.SECONDS);

        List<EntityLinkVersion> links = historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICE, id);
        assertThat(links.stream().filter(VersionRow::isOpen).map(VersionRow::getId))
                .containsExactlyInAnyOrder(kept.getId(), first.getId(), second.getId());
        assertThat(links).hasSize(3);
        assertThat(secondCommittedMeanwhile[0]).as("the second relink waited for the first's lock").isFalse();
        assertThat(historyReconciler.check().problems()).isEmpty();
    }

    @Test
    void twoPaymentsOnOneInvoiceAtOnceLeaveItsIntervalsContiguous() throws Exception {
        Invoice inv = invoice();
        CyclicBarrier start = new CyclicBarrier(2);

        List<Future<?>> running = List.of(
                threads.submit(payment(inv.getId(), "30.00", start)),
                threads.submit(payment(inv.getId(), "45.00", start)));
        for (Future<?> f : running) f.get(10, TimeUnit.SECONDS);

        List<InvoiceVersion> versions = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId());
        assertThat(versions).hasSize(3);
        assertThat(versions.stream().filter(VersionRow::isOpen)).hasSize(1);
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i - 1).getValidTo()).isEqualTo(versions.get(i).getValidFrom());
            assertThat(versions.get(i).getValidFrom()).isAfter(versions.get(i - 1).getValidFrom());
        }
        assertThat(versions.get(0).getPaidAmount()).isEqualByComparingTo("0");
        assertThat(versions.stream().map(v -> v.getPaidAmount().stripTrailingZeros().toPlainString()).toList().get(1))
                .isIn("30", "45");
        assertThat(versions.get(2).getPaidAmount()).isEqualByComparingTo("75");
        Map<String, Long> byTx = versions.stream().collect(Collectors.groupingBy(VersionRow::getTxId,
                Collectors.counting()));
        assertThat(byTx).hasSize(3);
    }

    /**
     * A line saved on its own leaves its invoice unlocked until the writer locks it. Here the second transaction
     * snapshots the invoice while the first still holds it, and waits for the lock: once it has it, it must
     * snapshot again, or its version would put the first transaction's line back as it was.
     */
    @Test
    void twoTransactionsChangingDifferentLinesOfOneInvoiceLeaveAVersionWithBoth() throws Exception {
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null),
                        new InvoiceDtos.LineInput(widget.getId(), 2, null))));
        List<Long> lines = tx.execute(status -> invoiceRepository.findById(inv.getId()).orElseThrow().getItems()
                .stream().map(InvoiceItem::getId).sorted().toList());
        CountDownLatch firstHoldsTheInvoice = new CountDownLatch(1);
        CountDownLatch secondSnapshotting = new CountDownLatch(1);
        historyFaults.onNextSnapshotOf(InvoiceHistory.CODE, inv.getId(), secondSnapshotting::countDown);

        Future<?> first = threads.submit(() -> {
            actAs(admin);
            try {
                tx.executeWithoutResult(status -> {
                    Invoice held = invoiceRepository.findById(inv.getId()).orElseThrow();
                    em.lock(held, LockModeType.PESSIMISTIC_WRITE);
                    line(held, lines.get(0)).setQuantity(5);
                    em.flush();
                    firstHoldsTheInvoice.countDown();
                    await(secondSnapshotting);
                    pause();   // the second is now waiting for the invoice's lock
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        });
        assertThat(firstHoldsTheInvoice.await(5, TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(status ->
                line(invoiceRepository.findById(inv.getId()).orElseThrow(), lines.get(1)).setQuantity(7));
        first.get(10, TimeUnit.SECONDS);

        List<InvoiceVersion> versions = historyTestSupport.versionsOf(InvoiceVersion.class, inv.getId());
        assertThat(versions).hasSize(3);
        assertThat(quantities(versions.get(1))).as("the first's version").containsExactly(5, 2);
        assertThat(quantities(versions.get(2))).as("the second's version").containsExactly(5, 7);
        assertThat(versions.get(2).isOpen()).isTrue();
        assertThat(versions.get(1).getValidTo()).isEqualTo(versions.get(2).getValidFrom());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static InvoiceItem line(Invoice invoice, Long id) {
        return invoice.getItems().stream().filter(l -> l.getId().equals(id)).findFirst().orElseThrow();
    }

    private static List<Integer> quantities(InvoiceVersion v) {
        return InvoiceHistory.items(v.getItemsJson()).stream().map(ItemSnapshot::quantity).toList();
    }

    /** Long enough for the other thread to reach its lock, far inside H2's lock timeout. */
    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the other thread never got there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Callable<Void> relink(Long promiseId, Long invoiceId, CyclicBarrier bothWritten) {
        return () -> {
            actAs(admin);
            try {
                tx.executeWithoutResult(status -> {
                    PaymentPromise p = promiseRepository.findById(promiseId).orElseThrow();
                    p.getInvoices().add(invoiceRepository.getReferenceById(invoiceId));
                    promiseRepository.saveAndFlush(p);
                    await(bothWritten);
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        };
    }

    private Callable<Void> payment(Long invoiceId, String amount, CyclicBarrier start) {
        return () -> {
            actAs(admin);
            try {
                await(start);
                paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal(amount),
                        "Cash", null, new ArrayList<>(List.of(invoiceId)), cara.getId(), null));
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the other thread never got there", e);
        }
    }

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }
}
