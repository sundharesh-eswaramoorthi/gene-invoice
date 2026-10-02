package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A transaction that does not commit leaves no version: history is written just before COMMIT, so a rollback
 * for any reason takes nothing with it, and a session that outlives a rolled-back transaction never writes that
 * transaction's history later (CF-22).
 */
class HistoryRollbackTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired HistoryTransactions historyTransactions;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    User admin;
    User sam;
    User cara;
    Product widget;
    Customer acme;

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        widget = product("Widget", "100");
        acme = customer("Acme Ltd");
    }

    @Test
    void aTransactionThatThrowsWritesNoVersion() {
        long before = versionCount();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Never saved");
            customerRepository.saveAndFlush(c);
            customerRepository.save(Customer.builder().name("Never made").region(defaultRegion()).build());
            throw new IllegalStateException("fails after the flush");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(versionCount()).isEqualTo(before);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Ltd");
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()))
                .extracting(CustomerVersion::getName).containsExactly("Acme Ltd");
    }

    @Test
    void aTransactionMarkedRollbackOnlyWritesNoVersion() {
        long before = versionCount();

        tx.executeWithoutResult(status -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Rolled back");
            customerRepository.saveAndFlush(c);
            status.setRollbackOnly();
        });

        assertThat(versionCount()).isEqualTo(before);
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId())).hasSize(1);
    }

    @Test
    void aRecomputeDryRunWritesNoVersionThoughItChangesPromisesBeforeRollingBack() {
        Invoice inv = invoice();
        Long id = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("50.00"),
                Today.date().plusDays(5), cara.getId(), null, List.of(inv.getId()))).id();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("50.00"), "Cash",
                null, List.of(inv.getId()), cara.getId(), null));
        // A promise whose figure is out of date, as recompute exists to repair; its history says the same.
        jdbc.update("update payment_promises set fulfilled_amount = 1.00 where id = ?", id);
        historyTestSupport.touch(PaymentPromise.class, List.of(id));
        long before = versionCount();

        List<PaymentPromiseService.RecomputeChange> changes = promiseService.recomputeAll(false);

        assertThat(changes).extracting(PaymentPromiseService.RecomputeChange::promiseId).containsExactly(id);
        assertThat(versionCount()).isEqualTo(before);
        assertThat(historyTestSupport.versionsOf(PromiseVersion.class, id).stream().filter(VersionRow::isOpen))
                .singleElement().satisfies(v -> assertThat(v.getFulfilledAmount()).isEqualByComparingTo("1.00"));
    }

    @Test
    void eachRowOfABulkActionHasATransactionOfItsOwnAndARowThatFailsLeavesNoVersion() throws Exception {
        Invoice a = invoice();
        Invoice paid = invoice();
        Invoice b = invoice();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(), new BigDecimal("10.00"), "Cash",
                null, List.of(paid.getId()), cara.getId(), null));
        List<String> txBefore = openTxIds(InvoiceVersion.class, paid.getId());

        mockMvc.perform(post("/api/invoices/bulk").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CANCEL", "ids", List.of(a.getId(), paid.getId(), b.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2));

        InvoiceVersion cancelledA = open(InvoiceVersion.class, a.getId());
        InvoiceVersion cancelledB = open(InvoiceVersion.class, b.getId());
        assertThat(cancelledA.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelledB.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelledA.getTxId()).isNotEqualTo(cancelledB.getTxId());
        assertThat(openTxIds(InvoiceVersion.class, paid.getId())).isEqualTo(txBefore);
    }

    @Test
    void aSessionThatOutlivesARolledBackTransactionWritesOnlyTheNextTransactionsHistory() {
        Customer globex = customer("Globex");
        EntityManager shared = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(shared));
        AtomicReference<String> rolledBackTx = new AtomicReference<>();
        AtomicReference<String> committedTx = new AtomicReference<>();
        try {
            // As open-in-view runs a request: one session, a transaction that rolls back, then one that commits.
            tx.executeWithoutResult(status -> {
                Customer c = customerRepository.findById(acme.getId()).orElseThrow();
                c.setName("Rolled back name");
                customerRepository.saveAndFlush(c);
                rolledBackTx.set(historyTransactions.current(em).txId());
                status.setRollbackOnly();
            });
            tx.executeWithoutResult(status -> {
                Customer c = customerRepository.findById(acme.getId()).orElseThrow();
                c.setName("Kept name");
                Customer g = customerRepository.findById(globex.getId()).orElseThrow();
                g.setPhone("555-0199");
                customerRepository.saveAndFlush(c);
                committedTx.set(historyTransactions.current(em).txId());
            });
        } finally {
            TransactionSynchronizationManager.unbindResource(emf);
            shared.close();
        }

        assertThat(committedTx.get()).isNotEqualTo(rolledBackTx.get());
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()))
                .extracting(CustomerVersion::getName).containsExactly("Acme Ltd", "Kept name");
        CustomerVersion kept = open(CustomerVersion.class, acme.getId());
        CustomerVersion phoned = open(CustomerVersion.class, globex.getId());
        assertThat(kept.getTxId()).isEqualTo(committedTx.get());
        assertThat(phoned.getTxId()).isEqualTo(committedTx.get());
        assertThat(allTxIds()).doesNotContain(rolledBackTx.get());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Invoice invoice() {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, sam.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private long versionCount() {
        return tx.execute(status -> {
            long n = 0;
            for (TypeHistory<?, ?> t : HistoryModel.types()) {
                n += em.createQuery("select count(v) from " + t.versionType().getSimpleName() + " v", Long.class)
                        .getSingleResult();
            }
            return n + em.createQuery("select count(l) from EntityLinkVersion l", Long.class).getSingleResult();
        });
    }

    private List<String> allTxIds() {
        return tx.execute(status -> {
            List<String> ids = new java.util.ArrayList<>();
            for (TypeHistory<?, ?> t : HistoryModel.types()) {
                ids.addAll(em.createQuery("select v.txId from " + t.versionType().getSimpleName() + " v", String.class)
                        .getResultList());
            }
            ids.addAll(em.createQuery("select l.txId from " + EntityLinkVersion.class.getSimpleName() + " l",
                    String.class).getResultList());
            return ids;
        });
    }

    private <V extends VersionRow> V open(Class<V> type, Long id) {
        List<V> open = historyTestSupport.versionsOf(type, id).stream().filter(VersionRow::isOpen).toList();
        assertThat(open).hasSize(1);
        return open.get(0);
    }

    private List<String> openTxIds(Class<? extends VersionRow> type, Long id) {
        return historyTestSupport.versionsOf(type, id).stream().map(VersionRow::getTxId).toList();
    }
}
