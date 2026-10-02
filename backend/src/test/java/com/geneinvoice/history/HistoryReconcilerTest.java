package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.ProductVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconciler repairs what SQL did behind history's back: a stale version gets a RECONCILE version, a record
 * with none gets one, a version of a record that is gone is closed, a doubled open version is closed down to one,
 * and a link row is put back. Each repair locks the row first and looks again, so a record a save put back in step
 * meanwhile is left alone (§5.7.8).
 */
class HistoryReconcilerTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    User admin;
    Product widget;
    Customer acme;
    Invoice invoice;
    Long promiseId;
    ExecutorService threads;

    @BeforeEach
    void family() {
        threads = Executors.newFixedThreadPool(2);
        admin = userRepository.findByUsername("admin").orElseThrow();
        User sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, "First order",
                sam.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(3), cara.getId(), null, List.of(invoice.getId()))).id();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private void bulk(String jpql, Object... params) {
        tx.executeWithoutResult(status -> {
            var q = em.createQuery(jpql);
            for (int i = 0; i < params.length; i += 2) q.setParameter((String) params[i], params[i + 1]);
            q.executeUpdate();
        });
    }

    @Test
    void aBulkUpdateIsFoundAndRepairedWithOneReconcileVersion() {
        Instant repairedAt = MovableClock.SHARED.instant().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        bulk("update Invoice i set i.notes = 'Changed in bulk' where i.id = :id", "id", invoice.getId());

        assertThat(historyReconciler.check().drifted()).containsExactly("INVOICE " + invoice.getId());
        MovableClock.SHARED.set(repairedAt);
        ReconcileReport report = historyReconciler.reconcile();

        assertThat(report.drifted()).containsExactly("INVOICE " + invoice.getId());
        assertThat(report.repaired()).isEqualTo(1);
        List<InvoiceVersion> versions = historyTestSupport.versionsOf(InvoiceVersion.class, invoice.getId());
        assertThat(versions).extracting(InvoiceVersion::getNotes).containsExactly("First order", "Changed in bulk");
        assertThat(versions.get(0).getValidTo()).isEqualTo(repairedAt);
        assertThat(versions.get(1)).satisfies(v -> {
            assertThat(v.getOrigin()).isEqualTo("RECONCILE");
            assertThat(v.getValidFrom()).isEqualTo(repairedAt);
            assertThat(v.isOpen()).isTrue();
            assertThat(v.getActorUserId()).isNull();
        });
        assertThat(historyReconciler.check().problems()).isEmpty();
        assertThat(historyReconciler.reconcile().repaired()).as("nothing left to repair").isZero();
    }

    @Test
    void aVersionOfSomethingGoneIsClosedAndSomethingWithoutOneGetsOne() {
        Product gone = product("Gizmo", "5");
        bulk("delete from Product p where p.id = :id", "id", gone.getId());
        bulk("delete from CustomerVersion v where v.id = :id", "id", acme.getId());
        ReconcileReport found = historyReconciler.check();
        assertThat(found.orphaned()).containsExactly("PRODUCT " + gone.getId());
        assertThat(found.missing()).containsExactly("CUSTOMER " + acme.getId());

        ReconcileReport report = historyReconciler.reconcile();

        assertThat(report.repaired()).isEqualTo(2);
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, gone.getId())).singleElement()
                .satisfies(v -> assertThat(v.isOpen()).as("closed").isFalse());
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId())).singleElement()
                .satisfies(v -> {
                    assertThat(v.getOrigin()).isEqualTo("RECONCILE");
                    assertThat(v.getName()).isEqualTo("Acme Ltd");
                    assertThat(v.isOpen()).isTrue();
                });
        assertThat(historyReconciler.check().problems()).isEmpty();
    }

    @Test
    void aDoubledVersionIsClosedDownToTheNewestAndALostLinkRowIsPutBack() {
        CustomerVersion open = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()).get(0);
        Instant later = open.getValidFrom().plusSeconds(60);
        tx.executeWithoutResult(status -> {
            CustomerVersion copy = new CustomerVersion();
            copy.setId(acme.getId());
            copy.setName(open.getName());
            copy.setCreditBalance(open.getCreditBalance());
            copy.setCreatedAt(open.getCreatedAt());
            copy.setRegionId(open.getRegionId());
            copy.setValidFrom(later);
            copy.setValidTo(VersionRow.OPEN);
            copy.setTxId("doubled");
            copy.setOrigin("USER");
            em.persist(copy);
        });
        bulk("delete from EntityLinkVersion l where l.ownerId = :owner", "owner", promiseId);
        ReconcileReport found = historyReconciler.check();
        assertThat(found.duplicates()).containsExactly("CUSTOMER " + acme.getId());
        assertThat(found.linkDrift()).singleElement().asString().startsWith("LINK:PROMISE_INVOICE " + promiseId);

        ReconcileReport report = historyReconciler.reconcile();

        assertThat(report.repaired()).isEqualTo(2);
        List<CustomerVersion> versions = historyTestSupport.versionsOf(CustomerVersion.class, acme.getId());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getValidTo()).as("ends where the newest begins").isEqualTo(later);
        assertThat(versions.get(1).isOpen()).isTrue();
        assertThat(historyTestSupport.linksOf(LinkHistory.PROMISE_INVOICES.kind(), promiseId)).singleElement()
                .satisfies(l -> {
                    assertThat(l.getId()).isEqualTo(invoice.getId());
                    assertThat(l.getOrigin()).isEqualTo("RECONCILE");
                    assertThat(l.isOpen()).isTrue();
                });
        assertThat(historyReconciler.check().problems()).isEmpty();
    }

    /**
     * The repair waits for a save holding the row, then finds the save has already put its history back in step,
     * and writes nothing for it.
     */
    @Test
    void aRowASaveBroughtBackInStepWhileTheRepairWaitedIsLeftAlone() throws Exception {
        bulk("update Invoice i set i.notes = 'Changed in bulk' where i.id = :id", "id", invoice.getId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> save = threads.submit(() -> tx.executeWithoutResult(status -> {
            Invoice held = em.find(Invoice.class, invoice.getId(), LockModeType.PESSIMISTIC_WRITE);
            held.setNotes("Fixed by hand");
            em.flush();
            locked.countDown();
            await(release);
        }));
        await(locked);

        Future<ReconcileReport> repair = threads.submit(() -> historyReconciler.reconcile());
        awaitWaitingOnALock(repair);
        release.countDown();
        save.get(5, TimeUnit.SECONDS);
        ReconcileReport report = repair.get(5, TimeUnit.SECONDS);

        assertThat(report.drifted()).as("it was drifted when checked").containsExactly("INVOICE " + invoice.getId());
        assertThat(report.repaired()).isZero();
        assertThat(historyTestSupport.versionsOf(InvoiceVersion.class, invoice.getId()))
                .extracting(InvoiceVersion::getNotes, InvoiceVersion::getOrigin)
                .as("the save's version, and none from the reconciler")
                .containsExactly(tuple("First order", "USER"),
                        tuple("Fixed by hand", "SYSTEM"));
        assertThat(historyReconciler.check().problems()).isEmpty();
    }

    @Test
    void anAdministratorCanAskForARepair() throws Exception {
        bulk("update Invoice i set i.notes = 'Changed in bulk' where i.id = :id", "id", invoice.getId());

        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/admin/history/reconcile").with(as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.get("drifted").asInt()).isEqualTo(1);
        assertThat(body.get("repaired").asInt()).isEqualTo(1);
        assertThat(body.get("missing").asInt()).isZero();
        assertThat(body.get("checked").asInt()).isPositive();
        assertThat(historyTestSupport.versionsOf(InvoiceVersion.class, invoice.getId()))
                .last().satisfies(v -> {
                    assertThat(v.getOrigin()).isEqualTo("RECONCILE");
                    assertThat(v.getActorUserId()).as("who asked for it").isEqualTo(admin.getId());
                });
        mockMvc.perform(post("/api/admin/history/reconcile").with(as(user("carl.cashier", "CASHIER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/history/reconcile").with(as(admin))
                        .param("asOf", Today.liveDate().minusDays(3).toString()))
                .andExpect(status().isOk());
    }

    /** Until some session waits on a lock; the repair finishing first means it never waited for the row. */
    private void awaitWaitingOnALock(Future<?> running) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (running.isDone()) {
                running.get();
                fail("the repair finished without waiting for the row's lock");
            }
            Long waiting = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null", Long.class);
            if (waiting != null && waiting > 0) return;
            Thread.onSpinWait();
        }
        fail("the repair was never seen waiting for the row's lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("the other thread never got there").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
