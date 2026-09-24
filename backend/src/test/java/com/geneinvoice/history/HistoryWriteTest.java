package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.automation.ActionSpec;
import com.geneinvoice.automation.AutomationDtos;
import com.geneinvoice.automation.AutomationEvent;
import com.geneinvoice.automation.AutomationRuleService;
import com.geneinvoice.automation.SubjectType;
import com.geneinvoice.automation.TriggerKind;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceItemHistory;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The write path of the mirror, end to end: what a save leaves behind, what a rollback does not,
 * and the two properties everything downstream of B3 rests on — exactly one open row per record,
 * and an interval chain with no gap and no overlap (B3).
 *
 * <p>THREE OF THESE ARE LOAD-BEARING and a reviewer should know which before simplifying
 * anything.
 *
 * <p>{@code aDirtyCheckOnlyUpdateWritesAHistoryRow} is the guard on
 * {@code HistoryTransactionManager.prepareSynchronization}, which sits under every transaction in
 * the application. Spring triggers beforeCommit BEFORE it flushes, so a transaction whose only
 * change is a dirty-checked update has fired no Hibernate event at the moment a lazily-registered
 * synchronization would have had to exist. If Spring's ordering contract ever shifts in a version
 * bump, history silently stops being written for every update in the product and only the
 * fifteen-minute reconciler notices. This test is the whole of the early warning.
 *
 * <p>{@code aRowOpenedBetweenOurCloseAndOurInsertIsRetriedNotDuplicated} is the only test that
 * exercises the savepoint and the bounded retry at all. It is also the one that has to be honest
 * about its own limits, and its Javadoc is.
 *
 * <p>{@code theOrderOfTheTwoSynchronizationsIsChangeFeedThenHistory} is the only test that fails
 * if either {@code getOrder()} constant moves. The two concurrency tests that the plan names are
 * genuine — two threads, one record, real transactions — but what they prove is that the mirror
 * survives concurrent writers, not that the retry ran: see their Javadoc and the honest gap
 * recorded with this unit.
 */
@Import({FixedHistoryClock.Config.class, HistoryWriteTest.Racing.class})
class HistoryWriteTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired CustomerService customerService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired AutomationRuleService ruleService;
    @Autowired HistoryRegistry registry;
    @Autowired HistoryClock clock;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ApprovalContext approvalContext;
    @Autowired com.geneinvoice.audit.AuditService auditService;

    @PersistenceContext EntityManager em;

    User admin;
    User collections;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        RacingHistoryWriter.disarm();
        admin = userRepository.findByUsername("admin").orElseThrow();
        collections = user("cora.collections9", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd");
        widget = product("Widget", "100.00");
        actAs(admin);
    }

    @AfterEach
    void unfreezeTheClock() {
        // The clock is a singleton in a cached context: a test that froze it and did not put it
        // back would date every later test's mirror rows at its own instant (B3).
        ((FixedHistoryClock) clock).release();
        RacingHistoryWriter.disarm();
        AsOfContext.clear();
    }

    // ---- what a save leaves behind -----------------------------------------------------------

    @Test
    void everySaveOfAMirroredEntityWritesAHistoryRow() {
        Invoice invoice = invoiceService.create(request(2));

        InvoiceHistory version = onlyOpen(InvoiceHistory.class, invoice.getId());
        assertThat(version.getId()).isEqualTo(invoice.getId());
        assertThat(version.getValidTo()).isEqualTo(HistoryRow.OPEN);
        assertThat(version.isDeleted()).isFalse();
        assertThat(version.isDrifted()).isFalse();
        // The person who pressed save, off SecurityContextHolder the way CurrentUser reads it.
        assertThat(version.getChangedByUserId()).isEqualTo(admin.getId());
        // Every projected column, not merely the fact that a row appeared.
        assertThat(version.getCustomerId()).isEqualTo(acme.getId());
        assertThat(version.getTotal()).isEqualByComparingTo("200.00");
        assertThat(version.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(version.getInvoiceNumber()).isEqualTo(invoice.getInvoiceNumber());
        // The denormalised as-of label, read through the still-open session off a LAZY
        // association — the one thing a detached fixture could never have proved (B3).
        assertThat(version.getCustomerName()).isEqualTo("Acme Ltd");
        assertThat(version.getSalesPocName()).isEqualTo(admin.getFullName());

        // The children of the aggregate are mirrored in the same drain, not left to a reconciler.
        assertThat(open(InvoiceItemHistory.class)).hasSize(1);
        assertThat(open(InvoiceItemHistory.class).get(0).getQuantity()).isEqualTo(2);
        // And the customer, whose credit balance the same transaction read but did not move.
        assertThat(open(CustomerHistory.class)).extracting(CustomerHistory::getId)
                .containsExactly(acme.getId());
    }

    /**
     * LOAD-BEARING. See the class Javadoc: this is the guard on
     * {@code HistoryTransactionManager.prepareSynchronization}, and there is nothing else in the
     * suite that fails if that override is removed (B3).
     *
     * <p>{@code CustomerService.update} is findById, setters, save→merge and nothing else. It
     * flushes only at commit, which is AFTER Spring has triggered beforeCommit, so at the moment
     * a synchronization registered by the listener would have had to exist, no listener had
     * fired. A lazily-registered design mirrors nothing here and looks perfectly healthy.
     */
    @Test
    void aDirtyCheckOnlyUpdateWritesAHistoryRow() {
        Customer subject = customer("Dirty Ltd");
        customerHistoryRepository.deleteAll();

        customerService.update(subject.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Dirty Ltd Renamed", "0900000000", null, null, null, null));

        CustomerHistory version = onlyOpen(CustomerHistory.class, subject.getId());
        assertThat(version.getName()).isEqualTo("Dirty Ltd Renamed");
        assertThat(version.getPhone()).isEqualTo("0900000000");
    }

    @Test
    void sixSavesOfOneInvoiceInsideOnePaymentWriteOneInvoiceHistoryRow() {
        Invoice first = invoiceService.create(request(1));
        Invoice second = invoiceService.create(request(1));
        clearMirrors();

        // One payment across two invoices: each invoice row is written several times inside the
        // one transaction (allocation, paid amount, status, and the promise re-evaluation that
        // follows), and the buffer collapses all of them into one version per record (B3).
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("150.00"), "NEFT", null,
                List.of(first.getId(), second.getId()), collections.getId(), null));

        assertThat(versions(InvoiceHistory.class, first.getId())).hasSize(1);
        assertThat(versions(InvoiceHistory.class, second.getId())).hasSize(1);
        assertThat(versions(PaymentHistory.class, paid.getId())).hasSize(1);
        // The SNAPSHOT IS THE FINAL STATE, which is the other half of coalescing: a design that
        // serialised at event time would have caught the invoice half-allocated.
        assertThat(onlyOpen(InvoiceHistory.class, first.getId()).getPaidAmount())
                .isEqualByComparingTo("100.00");
        assertThat(onlyOpen(InvoiceHistory.class, first.getId()).getStatus())
                .isEqualTo(InvoiceStatus.FULLY_PAID);
        assertThat(onlyOpen(InvoiceHistory.class, second.getId()).getPaidAmount())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void aRolledBackTransactionLeavesNoHistoryRow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        clearMirrors();

        assertThatThrownBy(() -> tx.execute(status -> {
            invoiceService.create(request(1));
            throw new IllegalStateException("deliberate, after the save and before the commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(invoiceRepository.count()).isZero();
        // The mirror row is written INSIDE the caller's transaction, so a rollback takes it with
        // it. Written after the commit instead, this would be a version of an invoice that never
        // existed, and no reconciler could ever tell it apart from one that did (B3).
        assertThat(invoiceHistoryRepository.count()).isZero();
        assertThat(invoiceItemHistoryRepository.count()).isZero();
    }

    @Test
    void voidingAPaymentWritesAHistoryRowWhoseStatusIsVoided() {
        Invoice invoice = invoiceService.create(request(1));
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("100.00"), "NEFT", null,
                List.of(invoice.getId()), collections.getId(), null));

        paymentService.voidPayment(paid.getId());

        List<PaymentHistory> chain = versions(PaymentHistory.class, paid.getId());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getStatus()).isEqualTo(PaymentStatus.ACTIVE);
        assertThat(chain.get(1).getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertChainIsContiguous(chain);
        // The invoice the reversal put back on the book has a version of its own, at the same
        // instant, because it really did change.
        assertThat(onlyOpen(InvoiceHistory.class, invoice.getId()).getStatus())
                .isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void updatingAPaymentAmountWritesAHistoryRow() {
        Payment paid = paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("100.00"), "NEFT", null, List.of(),
                collections.getId(), null));

        paymentService.updateAmount(paid.getId(), new BigDecimal("250.00"), "RTGS", "corrected");

        List<PaymentHistory> chain = versions(PaymentHistory.class, paid.getId());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(chain.get(1).getAmount()).isEqualByComparingTo("250.00");
        assertThat(chain.get(1).getMethod()).isEqualTo("RTGS");
        assertChainIsContiguous(chain);
    }

    @Test
    void deletingACustomerWritesATombstoneHistoryRow() {
        Customer doomed = customer("Gone Ltd");
        // CUSTOMER_DELETE is alwaysChecked, so a deletion only ever runs inside an approval's
        // replay. This is that replay's inner half exactly, and it is the only configuration in
        // which CustomerService.delete runs at all (B2).
        approvalContext.applying(null, () -> {
            customerService.delete(doomed.getId());
            return null;
        });

        List<CustomerHistory> chain = versions(CustomerHistory.class, doomed.getId());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).isDeleted()).isFalse();
        CustomerHistory tombstone = chain.get(1);
        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.getValidTo()).isEqualTo(HistoryRow.OPEN);
        // THE TOMBSTONE CARRIES THE VALUES THE RECORD HAD WHEN IT WENT, copied forward off the
        // row it closed, because B3 promises that a deleted record is SERVED as of a date before
        // it went and not merely disclosed. A row of nulls would also have been accepted by the
        // schema — and would then have been invisible to a region-narrowed caller, whose axis
        // reads customer_id (B3).
        assertThat(tombstone.getName()).isEqualTo("Gone Ltd");
        assertThat(tombstone.getCustomerId()).isEqualTo(doomed.getId());
        assertChainIsContiguous(chain);

        // Before it went it is in force; from the moment it went it is not. That is the whole
        // contract of a tombstone, and AsOf.at is what enforces it.
        assertThat(inForce(CustomerHistory.class, doomed.getId(),
                chain.get(0).getValidFrom())).hasSize(1);
        assertThat(inForce(CustomerHistory.class, doomed.getId(),
                tombstone.getValidFrom())).isEmpty();
    }

    @Test
    void removingAPromisesInvoiceLinkWritesALinkHistoryRow() {
        Invoice kept = invoiceService.create(request(1));
        Invoice dropped = invoiceService.create(request(1));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("200.00"), LocalDate.now().plusDays(7),
                collections.getId(), null, List.of(kept.getId(), dropped.getId())));

        assertThat(open(PromiseInvoiceHistory.class))
                .extracting(PromiseInvoiceHistory::getInvoiceId)
                .containsExactlyInAnyOrder(kept.getId(), dropped.getId());

        promiseService.update(promise.id(), new PromiseDtos.UpdatePromiseRequest(
                new BigDecimal("200.00"), LocalDate.now().plusDays(7), collections.getId(), null,
                List.of(kept.getId())));

        // The join table has no entity, so nothing Hibernate calls an insert or a delete ever
        // happens to it: the three collection events are the only signal there is (B3).
        List<PromiseInvoiceHistory> forDropped = all(PromiseInvoiceHistory.class).stream()
                .filter(r -> r.getInvoiceId().equals(dropped.getId())).toList();
        assertThat(forDropped).hasSize(2);
        assertThat(forDropped.get(0).isDeleted()).isFalse();
        assertThat(forDropped.get(1).isDeleted()).isTrue();
        assertChainIsContiguous(forDropped);

        // The link that did NOT change was not rewritten: reconciling is a diff, not a rewrite.
        List<PromiseInvoiceHistory> forKept = all(PromiseInvoiceHistory.class).stream()
                .filter(r -> r.getInvoiceId().equals(kept.getId())).toList();
        assertThat(forKept).hasSize(1);
        assertThat(forKept.get(0).getValidTo()).isEqualTo(HistoryRow.OPEN);

        // As of before the edit the promise covered both; as of after it, one.
        Instant before = forDropped.get(0).getValidFrom();
        Instant after = forDropped.get(1).getValidFrom();
        assertThat(inForce(PromiseInvoiceHistory.class, promise.id(), before)).hasSize(2);
        assertThat(inForce(PromiseInvoiceHistory.class, promise.id(), after)).hasSize(1);
    }

    /**
     * ONE SAVE IS ONE INSTANT, across every record it touched.
     *
     * <p>A payment across two invoices changes five records at once. If each of them were dated
     * from its own reading of the clock, an as-of read landing between two of those readings
     * would answer with a HALF-APPLIED transaction — a payment whose invoices had not been paid
     * down yet — which is the exact class of answer as-of exists to make impossible. This is not
     * hypothetical: reading the clock per record made the link mirror's two rows come out
     * microseconds apart, and "as of the moment the promise was made, it covered two invoices"
     * was then true only when the rows happened to be written in one of the two possible orders
     * (B3).
     */
    @Test
    void everyMirrorRowOfOneTransactionCarriesTheSameInstant() {
        Invoice first = invoiceService.create(request(1));
        Invoice second = invoiceService.create(request(1));
        clearMirrors();

        paymentService.record(new PaymentDtos.CreatePaymentRequest(
                acme.getId(), new BigDecimal("150.00"), "NEFT", null,
                List.of(first.getId(), second.getId()), collections.getId(), null));

        List<HistoryRow> written = new ArrayList<>();
        for (HistoryBinding binding : registry.all()) {
            written.addAll(all(binding.mirrorClass()));
        }
        assertThat(written).as("the payment, both invoices and both allocations").hasSize(5);
        assertThat(written).extracting(HistoryRow::getValidFrom).containsOnly(
                written.get(0).getValidFrom());
    }

    // ---- the intervals hold under concurrency --------------------------------------------------

    /**
     * Two threads, one invoice, two real transactions released together on a latch.
     *
     * <p>WHAT THIS PROVES AND WHAT IT DOES NOT. It proves the mirror survives concurrent writers:
     * exactly one open row, a chain with no gap and no overlap, and an as-of read at every
     * boundary instant that answers with exactly one version. It does NOT prove the bounded retry
     * ran, and cannot: two transactions that both mirror invoice X have both written row X, so
     * the database has already serialised them on that row long before either drain begins, and
     * the optimistic-lock retry below is what that serialisation looks like from here.
     * {@code aRowOpenedBetweenOurCloseAndOurInsertIsRetriedNotDuplicated} is the test for the
     * retry itself (B3).
     */
    @Test
    void twoConcurrentUpdatesToOneInvoiceLeaveExactlyOneOpenHistoryRow() throws Exception {
        Invoice invoice = invoiceService.create(request(1));

        raceOn(2, attempt -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status -> {
                Invoice fresh = invoiceRepository.findById(invoice.getId()).orElseThrow();
                fresh.setNotes("touched by " + attempt);
                invoiceRepository.save(fresh);
            });
        });

        List<InvoiceHistory> chain = versions(InvoiceHistory.class, invoice.getId());
        assertThat(chain).hasSize(3);                       // the create, then both updates
        assertExactlyOneOpenRow(chain);
        assertChainIsContiguous(chain);
        assertEveryBoundaryAnswersWithOneRow(InvoiceHistory.class, invoice.getId(), chain);
    }

    /**
     * The same race pointed at {@code CustomerService.update}, which is the case the design names
     * because it takes no business row lock of its own at all — nothing in that method reaches
     * for {@code lockCustomer}, so the only thing serialising two writers is the row's own
     * version counter (B3).
     */
    @Test
    void twoConcurrentUpdatesToOneCustomerLeaveExactlyOneOpenHistoryRow() throws Exception {
        Customer subject = customer("Contended Ltd");
        clearMirrors();

        raceOn(2, attempt -> {
            actAs(admin);
            customerService.update(subject.getId(), new CustomerDtos.CustomerUpdateRequest(
                    "Contended " + attempt, null, null, null, null, null));
        });

        List<CustomerHistory> chain = versions(CustomerHistory.class, subject.getId());
        assertThat(chain).hasSize(2);
        assertExactlyOneOpenRow(chain);
        assertChainIsContiguous(chain);
        assertEveryBoundaryAnswersWithOneRow(CustomerHistory.class, subject.getId(), chain);
    }

    /**
     * Postgres and H2 both store {@code timestamp(6)}. Two changes the clock cannot tell apart
     * must still produce a strictly increasing, non-overlapping chain, or
     * {@code [validFrom, validTo)} is EMPTY for the first of them and an as-of read at that
     * instant answers with neither version (B3).
     */
    @Test
    void twoChangesInsideTheSameMicrosecondProduceAStrictlyIncreasingInterval() {
        FixedHistoryClock frozen = (FixedHistoryClock) clock;
        frozen.freezeAt(Instant.parse("2026-02-01T10:00:00Z"));

        Customer subject = customer("Frozen Ltd");
        customerService.update(subject.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Frozen Ltd Two", null, null, null, null, null));
        customerService.update(subject.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Frozen Ltd Three", null, null, null, null, null));

        List<CustomerHistory> chain = versions(CustomerHistory.class, subject.getId());
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getValidFrom()).isEqualTo(Instant.parse("2026-02-01T10:00:00Z"));
        // One microsecond apart, because the clock could not move and the intervals had to.
        assertThat(chain.get(1).getValidFrom())
                .isEqualTo(Instant.parse("2026-02-01T10:00:00.000001Z"));
        assertThat(chain.get(2).getValidFrom())
                .isEqualTo(Instant.parse("2026-02-01T10:00:00.000002Z"));
        assertChainIsContiguous(chain);
        assertEveryBoundaryAnswersWithOneRow(CustomerHistory.class, subject.getId(), chain);
    }

    /**
     * The retry, deterministically, and this Javadoc says exactly how far the determinism goes.
     *
     * <p>THE WINDOW CANNOT BE REACHED FROM THE BUSINESS PATH. A second writer can only be about
     * to mirror invoice X if it has already written row X, and the database serialised the two of
     * them on that row before either drain started. So the competing open row is planted here, in
     * the window itself, through the one seam {@code HistoryWriter.insert} exists to offer.
     *
     * <p>WHAT IS REAL: the unique constraint fires, the savepoint rolls the failed insert back
     * without poisoning the transaction, the close that preceded it SURVIVES the rollback, the
     * bounded loop tries again, and the record ends with exactly one open row and a chain with no
     * gap — which is what the carried-forward timestamp is for. WHAT IS SIMULATED: the competitor
     * is this transaction's own connection rather than another one, because a genuinely
     * concurrent INSERT into that window blocks on our uncommitted close, which is the very
     * property the design relies on and not something a single-JVM H2 test can wait out (B3).
     */
    @Test
    void aRowOpenedBetweenOurCloseAndOurInsertIsRetriedNotDuplicated() {
        Customer subject = customer("Raced Ltd");
        List<CustomerHistory> beforeTheRace = versions(CustomerHistory.class, subject.getId());
        assertThat(beforeTheRace).hasSize(1);

        RacingHistoryWriter.armFor("customer_history", subject.getId());
        customerService.update(subject.getId(), new CustomerDtos.CustomerUpdateRequest(
                "Raced Ltd Renamed", null, null, null, null, null));

        // Two inserts for one version: the first lost to the constraint, the second won.
        assertThat(RacingHistoryWriter.inserts.get()).isEqualTo(2);

        List<CustomerHistory> chain = versions(CustomerHistory.class, subject.getId());
        assertThat(chain).hasSize(2);
        assertExactlyOneOpenRow(chain);
        // No gap, although the first attempt had already closed the predecessor: the instant is
        // computed once and carried across attempts for exactly this reason (B3).
        assertChainIsContiguous(chain);
        assertThat(chain.get(1).getName()).isEqualTo("Raced Ltd Renamed");
        assertEveryBoundaryAnswersWithOneRow(CustomerHistory.class, subject.getId(), chain);
    }

    // ---- the guards ------------------------------------------------------------------------------

    /**
     * The third of the three guards on the ambient read-side clock, and the loudest. As-of is a
     * READ mode; a mirror row dated from a replayed past would make the past disagree with
     * itself, and nothing downstream could tell which of the two answers was the real one (B3).
     */
    @Test
    void theHistoryWriterRefusesToRunWhileAnAsOfReadIsInFlight() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> {
            try (AsOfContext.Handle ignored = AsOfContext.open(LocalDate.of(2026, 1, 31))) {
                tx.executeWithoutResult(status -> customer("Backdated Ltd"));
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("as-of is a read mode");

        // And it took the save with it, which is the point: a write that reached here under an
        // open context is a bug, and half of it committing would be worse than none of it.
        assertThat(customerRepository.findAll()).extracting(Customer::getName)
                .doesNotContain("Backdated Ltd");
    }

    /**
     * A bulk run is a loop of PROPAGATION_REQUIRES_NEW transactions, so each row gets its own
     * synchronization list, its own buffer and its own drain — which is what makes one row's
     * mirror rows commit with that row and not with the click (A5, B3 INTEGRATION).
     */
    @Test
    void aBulkRowWritesItsOwnMirrorRowsInItsOwnTransaction() throws Exception {
        Invoice first = invoiceService.create(request(1));
        Invoice second = invoiceService.create(request(1));
        Invoice third = invoiceService.create(request(1));
        // The middle row is already cancelled, so its transaction rolls back as "did not qualify"
        // while its neighbours commit.
        invoiceService.cancel(second.getId());
        clearMirrors();

        mockMvc.perform(post("/api/invoices/bulk").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BulkDtos.BulkRequest("CANCEL",
                                List.of(first.getId(), second.getId(), third.getId()),
                                false, null, List.of(), Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1));

        // Two rows changed, two rows mirrored; the row whose transaction rolled back left
        // nothing, in its own transaction, without taking its neighbours' rows with it.
        assertThat(open(InvoiceHistory.class)).extracting(InvoiceHistory::getId)
                .containsExactlyInAnyOrder(first.getId(), third.getId());
        assertThat(onlyOpen(InvoiceHistory.class, first.getId()).getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    /**
     * The blueprint's resolution of the ChangeFeed interaction, asserted on the buffer itself
     * rather than on its consequences.
     *
     * <p>Part A inserts {@code automation_events} rows inside ITS beforeCommit, which fires
     * POST_INSERT while this buffer is still open; audit rows, notifications and pending changes
     * arrive the same way. The listener filters on {@code isMirrored} BEFORE it buffers anything,
     * so none of them can ever enter — which matters because a key buffered after the drain has
     * already walked the buffer is a key nothing will ever write, and no reconciler can tell that
     * apart from real drift (A5, B3 INTEGRATION).
     */
    @Test
    void theBufferNeverHoldsAKeyForAnUnmirroredEntity() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            invoiceService.create(request(1));
            // An audit row, a notification and an outbox row are all written by ordinary saves on
            // this path and none of them is a record anybody can ask the state of as of a date.
            auditService.record("THING", 4242L, "POKED", null, Map.of("a", 1),
                    admin.getId(), null, null);
            em.flush();

            HistoryBuffer buffer = HistorySynchronization.bufferOrNull();
            assertThat(buffer).isNotNull();
            assertThat(buffer.keysSorted()).isNotEmpty();
            assertThat(buffer.keysSorted()).allMatch(k -> registry.isMirrored(k.entityType()),
                    "every buffered key is a mirrored entity");
            assertThat(buffer.keysSorted()).extracting(HistoryBuffer.Key::entityType)
                    .doesNotContain(com.geneinvoice.audit.AuditLog.class, AutomationEvent.class);
        });
    }

    @Test
    void aSaveThatTriggersARuleWritesBothTheEventRowAndTheMirrorRows() {
        ruleService.create(new AutomationDtos.SaveRuleRequest(
                "Chase every invoice", null, SubjectType.INVOICE,
                TriggerKind.ON_CREATED_OR_UPDATED, null, null, condition(),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, List.of()));
        automationEventRepository.deleteAll();
        clearMirrors();

        Invoice invoice = invoiceService.create(request(1));

        // Part A's outbox row AND B3's mirror rows, from one save, in one transaction.
        assertThat(automationEventRepository.findAll()).extracting(AutomationEvent::getSubjectId)
                .contains(invoice.getId());
        assertThat(versions(InvoiceHistory.class, invoice.getId())).hasSize(1);
        // The outbox insert happens inside ChangeFeed's own beforeCommit, which runs BEFORE this
        // drain and therefore fires POST_INSERT while the buffer is still being filled. The
        // listener's isMirrored filter is what stops that key entering the buffer, and an
        // automation_events row is not a thing anybody can ask the state of as of a date (A5, B3).
        assertThat(registry.isMirrored(AutomationEvent.class)).isFalse();
        assertThat(registry.all()).noneMatch(b -> b.mirrorTable().startsWith("automation"));
    }

    /**
     * LOAD-BEARING, and the only test that fails if either {@code getOrder()} constant moves.
     *
     * <p>A probe registered strictly between the two watches the transaction's own connection: by
     * the time it runs, Part A's outbox row must already be inserted and this record's mirror row
     * must not yet exist. Reverse the two orders and the drain runs over a context Part A is
     * still about to add to — a key buffered after the walk, written by nobody, and found
     * fifteen minutes later by a reconciler that cannot tell it from real drift (A5, B3
     * INTEGRATION).
     */
    @Test
    void theOrderOfTheTwoSynchronizationsIsChangeFeedThenHistory() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        clearMirrors();
        automationEventRepository.deleteAll();
        int[] seen = new int[2];

        Invoice invoice = tx.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    return Ordered.LOWEST_PRECEDENCE - 50;      // between the two, on purpose
                }

                @Override
                public void beforeCommit(boolean readOnly) {
                    seen[0] = countOnThisConnection("select count(*) from automation_events");
                    seen[1] = countOnThisConnection("select count(*) from invoice_history");
                }
            });
            return invoiceService.create(request(1));
        });

        assertThat(seen[0]).as("ChangeFeed had already published when the probe ran").isPositive();
        assertThat(seen[1]).as("the history drain had NOT yet run when the probe ran").isZero();
        assertThat(versions(InvoiceHistory.class, invoice.getId())).hasSize(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Two threads released together, each retrying its own transaction until it commits.
     *
     * <p>The retry is not a weakening of the race, it is what the race IS: both entities carry an
     * optimistic-lock version, so two genuinely simultaneous updates to one row cannot both
     * succeed and the loser is told to read again. The latch is what guarantees the two overlap
     * at all (B3).
     */
    private void raceOn(int threads, java.util.function.IntConsumer body) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        List<Thread> running = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    for (int attempt = 1; ; attempt++) {
                        try {
                            body.accept(index);
                            return;
                        } catch (RuntimeException e) {
                            if (attempt >= 20) throw e;
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            }, "history-race-" + i);
            running.add(thread);
            thread.start();
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread thread : running) thread.join(30_000);
        synchronized (failures) {
            assertThat(failures).isEmpty();
        }
    }

    private int countOnThisConnection(String sql) {
        Connection c = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = c.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(c, dataSource);
        }
    }

    private <T extends HistoryRow> void assertExactlyOneOpenRow(List<T> chain) {
        assertThat(chain).filteredOn(r -> r.getValidTo().equals(HistoryRow.OPEN)).hasSize(1);
        assertThat(chain.get(chain.size() - 1).getValidTo()).isEqualTo(HistoryRow.OPEN);
    }

    /** No gap and no overlap: one version ends exactly where the next begins (B3). */
    private <T extends HistoryRow> void assertChainIsContiguous(List<T> chain) {
        for (int i = 0; i < chain.size() - 1; i++) {
            assertThat(chain.get(i).getValidFrom())
                    .as("version %d starts before version %d", i, i + 1)
                    .isBefore(chain.get(i + 1).getValidFrom());
            assertThat(chain.get(i).getValidTo())
                    .as("version %d ends exactly where version %d begins", i, i + 1)
                    .isEqualTo(chain.get(i + 1).getValidFrom());
        }
    }

    /**
     * At the instant each version opens, and one microsecond before the whole chain ends, exactly
     * one row is in force. This is the first execution {@code AsOf.at} has ever had against a
     * real mirror root, and the half-open interval is what makes the answer one and not two (B3).
     */
    private <T extends HistoryRow> void assertEveryBoundaryAnswersWithOneRow(
            Class<T> mirror, Long id, List<T> chain) {
        for (T version : chain) {
            assertThat(inForce(mirror, id, version.getValidFrom()))
                    .as("exactly one version of %s is in force at %s", id, version.getValidFrom())
                    .hasSize(1);
            // And one microsecond before it opens, the PREVIOUS one is — never both.
            assertThat(inForce(mirror, id, version.getValidFrom().minusNanos(1_000)))
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    private <T> List<T> inForce(Class<T> mirror, Long id, Instant at) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(mirror);
        Root<T> root = cq.from(mirror);
        cq.where(cb.and(cb.equal(root.get("id"), id), AsOf.at(at).build(root, cq, cb)));
        return em.createQuery(cq).getResultList();
    }

    /** Every version of one record, oldest first. */
    private <T extends HistoryRow> List<T> versions(Class<T> mirror, Long id) {
        return all(mirror).stream().filter(r -> r.getId().equals(id)).toList();
    }

    private <T extends HistoryRow> List<T> open(Class<T> mirror) {
        return all(mirror).stream().filter(r -> r.getValidTo().equals(HistoryRow.OPEN)).toList();
    }

    private <T extends HistoryRow> T onlyOpen(Class<T> mirror, Long id) {
        List<T> found = versions(mirror, id).stream()
                .filter(r -> r.getValidTo().equals(HistoryRow.OPEN)).toList();
        assertThat(found).as("exactly one open row of %s %s", mirror.getSimpleName(), id).hasSize(1);
        return found.get(0);
    }

    private <T extends HistoryRow> List<T> all(Class<T> mirror) {
        em.clear();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(mirror);
        Root<T> root = cq.from(mirror);
        cq.orderBy(cb.asc(root.get("validFrom")), cb.asc(root.get("historyId")));
        return em.createQuery(cq).getResultList().stream()
                .sorted(Comparator.comparing(HistoryRow::getValidFrom)
                        .thenComparing(HistoryRow::getHistoryId))
                .toList();
    }

    /** What the fixtures above have already mirrored, cleared so a test counts only its own. */
    private void clearMirrors() {
        customerHistoryRepository.deleteAll();
        invoiceHistoryRepository.deleteAll();
        invoiceItemHistoryRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        paymentAllocationHistoryRepository.deleteAll();
        promiseHistoryRepository.deleteAll();
        promiseInvoiceHistoryRepository.deleteAll();
        promisePaymentHistoryRepository.deleteAll();
        disputeHistoryRepository.deleteAll();
        customerPocHistoryRepository.deleteAll();
        taskHistoryRepository.deleteAll();
    }

    private InvoiceDtos.CreateInvoiceRequest request(int quantity) {
        return new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, new BigDecimal("100.00"))));
    }

    private JsonNode condition() {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"balance:gt:0\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the competing writer ---------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class Racing {
        @Bean
        @Primary
        RacingHistoryWriter racingHistoryWriter(DataSource dataSource, HistoryRegistry registry,
                                                HistoryClock clock,
                                                com.geneinvoice.auth.CurrentUser currentUser) {
            return new RacingHistoryWriter(dataSource, registry, clock, currentUser);
        }
    }

    /**
     * A {@link HistoryWriter} that opens a competing row in the window between the close and the
     * insert, on the one occasion a test arms it for. Disarmed it is the production writer,
     * statement for statement (B3).
     */
    static class RacingHistoryWriter extends HistoryWriter {

        static final AtomicInteger inserts = new AtomicInteger();
        private static volatile String table;
        private static volatile Long record;
        private static volatile boolean planted;

        RacingHistoryWriter(DataSource dataSource, HistoryRegistry registry, HistoryClock clock,
                            com.geneinvoice.auth.CurrentUser currentUser) {
            super(dataSource, registry, clock, currentUser);
        }

        static void armFor(String mirrorTable, Long id) {
            table = mirrorTable;
            record = id;
            planted = false;
            inserts.set(0);
        }

        static void disarm() {
            table = null;
            record = null;
            planted = false;
            inserts.set(0);
        }

        @Override
        void insert(Connection c, HistoryBinding b, Long id, Long otherId, List<Object> values,
                    Instant t, boolean deleted, Long actor) throws SQLException {
            if (!planted && b.mirrorTable().equals(table) && id.equals(record)) {
                planted = true;
                try (PreparedStatement statement = c.prepareStatement(
                        "insert into " + b.mirrorTable() + " (" + b.businessIdColumn()
                                + ", valid_from, valid_to, deleted, drifted) values (?, ?, ?, ?, ?)")) {
                    statement.setLong(1, id);
                    HistoryJdbc.setInstant(statement, 2, t.minus(Duration.ofSeconds(1)));
                    HistoryJdbc.setInstant(statement, 3, HistoryRow.OPEN);
                    statement.setBoolean(4, false);
                    statement.setBoolean(5, false);
                    statement.executeUpdate();
                }
            }
            if (b.mirrorTable().equals(table) && id.equals(record)) inserts.incrementAndGet();
            super.insert(c, b, id, otherId, values, t, deleted, actor);
        }
    }
}
