package com.geneinvoice.history;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fifteen-minute sweep that turns "the write path is complete by construction" from a claim
 * into a claim somebody checks (B3).
 *
 * <p>WHAT EACH TEST IS FOR. The write path covers every write Hibernate mediates, and the raw-JDBC
 * schema upgrades, a future native query and a restore from a backup are outside it by
 * construction. Every fixture here reaches around Hibernate with raw JDBC on purpose: that is
 * exactly the shape of the failure the reconciler exists to find, and it is the only way to
 * produce it without breaking the write path first.
 *
 * <p>All four are behavioural and all four fail if the reconciler stops doing its job. Three of
 * them are mutation-checked in both directions — the repair itself, the {@code drifted} flag that
 * makes the answer inexact, and the tombstone's carried-forward values (B3).
 */
class HistoryReconcilerTest extends IntegrationTestBase {

    @Autowired HistoryReconciler reconciler;
    @Autowired HistoryRegistry registry;
    @Autowired HistoryDrift drift;
    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired DataSource dataSource;

    @PersistenceContext EntityManager em;

    private User admin;
    private User collections;
    private Customer acme;
    private Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cora.reconcile", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    @AfterEach
    void clearTheReadClock() {
        AsOfContext.clear();
    }

    // ---- the forward pass -------------------------------------------------------------------

    /**
     * A raw-JDBC update is precisely what the five {@code *SchemaUpgrade} beans do, and Hibernate
     * raises no event for it — so the mirror keeps serving the old value with no sign that
     * anything is wrong. The reconciler notices, repairs, and MARKS the repair: an answer built
     * over a repaired row says {@code exact: false}, because the predecessor was closed when the
     * sweep noticed rather than when the change happened (B3).
     */
    @Test
    void anUpdateWhosePathBypassesHibernateIsRepairedByTheReconcilerAndReportedAsNotExact() {
        Invoice invoice = invoiceService.create(request(2));
        assertThat(onlyOpen(InvoiceHistory.class, invoice.getId()).getTotal())
                .isEqualByComparingTo("200.00");

        execute("update invoices set total = 999.00 where id = " + invoice.getId());
        // Nothing has noticed yet, and that is the point: the mirror and the record disagree and
        // every as-of answer over this invoice is quietly wrong.
        assertThat(onlyOpen(InvoiceHistory.class, invoice.getId()).getTotal())
                .isEqualByComparingTo("200.00");

        int repaired = reconciler.reconcile(registry.forType(Invoice.class));

        assertThat(repaired).isEqualTo(1);
        List<InvoiceHistory> chain = versions(InvoiceHistory.class, invoice.getId());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getTotal()).isEqualByComparingTo("200.00");
        InvoiceHistory now = chain.get(1);
        assertThat(now.getTotal()).isEqualByComparingTo("999.00");
        assertThat(now.getValidTo()).isEqualTo(HistoryRow.OPEN);
        assertThat(now.isDeleted()).isFalse();
        // The flag is the whole difference between a repair and a lie: a row nobody watched change.
        assertThat(now.isDrifted()).isTrue();
        // No gap and no overlap: an as-of read at the boundary instant finds exactly one version.
        assertThat(chain.get(0).getValidTo()).isEqualTo(now.getValidFrom());
        assertThat(inForce(InvoiceHistory.class, now.getValidFrom())).containsExactly(invoice.getId());
        assertThat(inForce(InvoiceHistory.class, now.getValidFrom().minusNanos(1_000)))
                .containsExactly(invoice.getId());
        // Repaired through the SAME projection the seed writes, denormalised labels and all, so a
        // repaired row and a seeded row of one record are the same row (B3).
        assertThat(now.getCustomerName()).isEqualTo("Acme Ltd");
        assertThat(now.getSalesPocName()).isEqualTo(admin.getFullName());
        assertThat(now.getCustomerId()).isEqualTo(acme.getId());
        assertThat(now.getChangedByUserId()).as("nobody made this version; it was inferred").isNull();

        assertThat(drift.anyDriftedAtOrBefore(InvoiceHistory.class, Instant.now())).isTrue();
        assertThat(drift.anyDriftedAtOrBefore(CustomerHistory.class, Instant.now()))
                .as("one table's repair does not downgrade another's answer").isFalse();

        // And the downgrade reaches the wire: the response says so, once, naming the table.
        try (AsOfContext.Handle open = AsOfContext.open(LocalDate.now(ZoneOffset.UTC))) {
            drift.markIfDrifted(InvoiceHistory.class, AsOfContext.instant());
            assertThat(AsOfContext.info().exact()).isFalse();
            assertThat(AsOfContext.info().notes())
                    .anyMatch(note -> note.contains("invoice_history"));
        }
    }

    /**
     * A record the write path never saw at all — the shape a failed seed, a restore or a bulk
     * insert leaves behind. The reconciler gives it its first version rather than leaving it
     * invisible to every as-of read for ever (B3).
     */
    @Test
    void aRecordWithNoMirrorRowAtAllIsGivenItsFirstVersionByTheReconciler() {
        Customer orphan = customer("Orphan Ltd");
        customerHistoryRepository.deleteAll();
        assertThat(versions(CustomerHistory.class, orphan.getId())).isEmpty();

        reconciler.reconcile(registry.forType(Customer.class));

        CustomerHistory first = onlyOpen(CustomerHistory.class, orphan.getId());
        assertThat(first.getName()).isEqualTo("Orphan Ltd");
        // Read as a column rather than through getRegionId(), which is the @Transient the slice
        // fills from customer_region_history and is deliberately not this column (B1, B3).
        assertThat(scalar("select region_id from customer_history where customer_id = "
                + orphan.getId())).isEqualTo(defaultRegion().getId());
        // Dated from when it was NOTICED and flagged, because when it really happened is exactly
        // what nobody knows. The seed is the only thing entitled to date a row from creation (B3).
        assertThat(first.isDrifted()).isTrue();
    }

    // ---- the inverse pass -------------------------------------------------------------------

    /**
     * A hard delete through a path Hibernate never saw looks like nothing at all from the forward
     * direction — there is no live row left to disagree with anything. The inverse pass closes the
     * open row and writes a tombstone that CARRIES THE VALUES FORWARD, so the allocation is still
     * readable as of a date before it went (B3).
     */
    @Test
    void aHardDeletedAllocationIsTombstonedByTheReconciler() {
        Invoice invoice = invoiceService.create(request(1));
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("100.00"), "NEFT", null,
                List.of(invoice.getId()), collections.getId(), null));
        Long allocationId = allocationIdOf(paid);
        assertThat(onlyOpen(PaymentAllocationHistory.class, allocationId).isDeleted()).isFalse();

        execute("delete from payment_allocations where id = " + allocationId);

        int repaired = reconciler.reconcile(registry.forType(PaymentAllocation.class));

        assertThat(repaired).isEqualTo(1);
        List<PaymentAllocationHistory> chain = versions(PaymentAllocationHistory.class, allocationId);
        assertThat(chain).hasSize(2);
        PaymentAllocationHistory tomb = chain.get(1);
        assertThat(tomb.isDeleted()).isTrue();
        assertThat(tomb.isDrifted()).isTrue();
        assertThat(tomb.getValidTo()).isEqualTo(HistoryRow.OPEN);
        assertThat(chain.get(0).getValidTo()).isEqualTo(tomb.getValidFrom());
        // THE VALUES IT HAD WHEN IT WENT. A row of nulls would have been accepted by every column
        // on this table and would have made the tombstone invisible to a region-narrowed caller,
        // whose axis reads customer_id (B3).
        assertThat(tomb.getAmount()).isEqualByComparingTo("100.00");
        assertThat(tomb.getInvoiceId()).isEqualTo(invoice.getId());
        assertThat(tomb.getPaymentId()).isEqualTo(paid.getId());
        assertThat(tomb.getCustomerId()).isEqualTo(acme.getId());
        assertThat(tomb.getCustomerName()).isEqualTo("Acme Ltd");

        // Served as of a date before it went, absent after: the half-open interval, over a record
        // the live tables no longer have at all.
        assertThat(inForce(PaymentAllocationHistory.class, tomb.getValidFrom().minusNanos(1_000)))
                .containsExactly(allocationId);
        assertThat(inForce(PaymentAllocationHistory.class, tomb.getValidFrom())).isEmpty();
    }

    // ---- the properties a sweep has to have -------------------------------------------------

    /**
     * Run against a book nobody has tampered with, the sweep writes NOTHING — for all eleven
     * mirrors, both passes. A reconciler that repaired on every pass would double the size of
     * every mirror every fifteen minutes and make every as-of answer inexact within the hour (B3).
     */
    @Test
    void theReconcilerIsIdempotent() {
        Invoice invoice = invoiceService.create(request(2));
        paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("50.00"), "NEFT", null,
                List.of(invoice.getId()), collections.getId(), null));

        // SETTLE FIRST, and the reason is worth writing down: the shared test context carries rows
        // other classes created that IntegrationTestBase does not delete — disputes are never
        // deleted by the harness while dispute_history is cleared before every test — so the first
        // sweep of a run legitimately has repairs to make that have nothing to do with this test.
        // What is asserted here is the property that matters: a sweep over a book this sweep has
        // already agreed with writes NOTHING, for all eleven mirrors and both passes (B3).
        sweepEverything();
        assertThat(sweepEverything()).as("a settled book needs no repair").isZero();

        execute("update invoices set status = 'CANCELLED' where id = " + invoice.getId());
        assertThat(sweepEverything()).isEqualTo(1);

        // The second pass sees what the first one wrote and agrees with it.
        assertThat(sweepEverything()).isZero();
        assertThat(sweepEverything()).isZero();
        // Three versions and no more: raised, paid down, then the repair. A sweep that wrote on
        // every pass would add one per fifteen minutes for ever (B3).
        List<InvoiceHistory> chain = versions(InvoiceHistory.class, invoice.getId());
        assertThat(chain).hasSize(3);
        assertThat(chain.get(2).isDrifted()).isTrue();
        assertThat(chain.get(0).isDrifted()).isFalse();
        assertThat(chain.get(1).isDrifted()).isFalse();
    }

    /** A repair is a WARN naming the table and the id, or nobody ever learns it happened (B3). */
    @Test
    void theReconcilerNamesTheTableAndTheIdItRepaired() {
        Invoice invoice = invoiceService.create(request(1));
        execute("update invoices set paid_amount = 7.00 where id = " + invoice.getId());

        List<String> logged = recorded(() -> reconciler.reconcile(registry.forType(Invoice.class)));

        assertThat(logged).hasSize(1);
        assertThat(logged.get(0))
                .startsWith("WARN ")
                .contains("invoice_history")
                .contains(String.valueOf(invoice.getId()));

        // And a sweep that repairs nothing says nothing: a WARN that fires on every healthy sweep
        // is a WARN everybody learns to ignore (B3).
        assertThat(recorded(() -> reconciler.reconcile(registry.forType(Invoice.class)))).isEmpty();
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private int sweepEverything() {
        int repaired = 0;
        for (HistoryBinding binding : registry.all()) repaired += reconciler.reconcile(binding);
        return repaired;
    }

    private InvoiceDtos.CreateInvoiceRequest request(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00"))));
    }

    /** The allocation the payment wrote, read back through the mirror the writer filled (B3). */
    private Long allocationIdOf(Payment paid) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<PaymentAllocationHistory> root = cq.from(PaymentAllocationHistory.class);
        cq.select(root.get("id")).where(cb.equal(root.get("paymentId"), paid.getId()));
        return em.createQuery(cq).getSingleResult();
    }

    private Long scalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private <T extends HistoryRow> List<T> versions(Class<T> mirror, Long id) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(mirror);
        Root<T> root = cq.from(mirror);
        cq.where(cb.equal(root.get("id"), id));
        return em.createQuery(cq).getResultList().stream()
                .sorted(Comparator.comparing(HistoryRow::getValidFrom)
                        .thenComparing(HistoryRow::getHistoryId))
                .toList();
    }

    private <T extends HistoryRow> T onlyOpen(Class<T> mirror, Long id) {
        List<T> found = versions(mirror, id).stream()
                .filter(row -> row.getValidTo().equals(HistoryRow.OPEN)).toList();
        assertThat(found).as("exactly one open row of %s %s", mirror.getSimpleName(), id).hasSize(1);
        return found.get(0);
    }

    private List<Long> inForce(Class<? extends HistoryRow> mirror, Instant at) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<? extends HistoryRow> root = cq.from(mirror);
        cq.select(root.get("id"))
                .where(com.geneinvoice.common.asof.AsOf.at(at).build(root, cq, cb));
        return em.createQuery(cq).getResultList();
    }

    /** What the reconciler logged while the body ran, as "LEVEL message", and nowhere else. */
    private static List<String> recorded(Runnable body) {
        Logger logger = (Logger) LoggerFactory.getLogger(HistoryReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
        }
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }
}
