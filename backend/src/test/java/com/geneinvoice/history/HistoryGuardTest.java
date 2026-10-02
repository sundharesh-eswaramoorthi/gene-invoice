package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.types.CustomerHistory;
import com.geneinvoice.history.versions.CustomerVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * History is never written while a past point is being viewed, and a version that cannot be built fails the
 * save in tests (strict) while production skips just that record and carries on (§5.7.6).
 */
class HistoryGuardTest extends IntegrationTestBase {

    @Autowired TransactionTemplate tx;
    @Autowired HistoryTransactions historyTransactions;
    @Autowired HistoryTracker historyTracker;
    @Autowired VersionStore versionStore;
    @Autowired CurrentUser currentUser;
    @Autowired Clock clock;
    @PersistenceContext EntityManager em;

    Customer acme;
    Customer globex;

    @BeforeEach
    void family() {
        actAs(userRepository.findByUsername("admin").orElseThrow());
        acme = customer("Acme Ltd");
        globex = customer("Globex");
    }

    @Test
    void aSaveWhileViewingThePastThrowsAndChangesNothing() {
        AsOfPoint yesterday = AsOfPoint.endOfDay(Today.date().minusDays(1));
        long customers = customerRepository.count();

        assertThatThrownBy(() -> AsOf.with(yesterday, () -> customerRepository.save(
                Customer.builder().name("Made in the past").region(defaultRegion()).build())))
                .satisfies(e -> assertThat(causes(e)).anyMatch(c -> c instanceof AsOfException a
                        && AsOfException.READ_ONLY.equals(a.code)));
        assertThatThrownBy(() -> AsOf.with(yesterday, () -> tx.execute(status -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Renamed in the past");
            return customerRepository.saveAndFlush(c);
        }))).satisfies(e -> assertThat(causes(e)).anyMatch(AsOfException.class::isInstance));
        assertThatThrownBy(() -> AsOf.with(yesterday, () -> tx.execute(status -> {
            historyTracker.touch(Customer.class, List.of(acme.getId()), false);
            return null;
        }))).isInstanceOf(AsOfException.class);

        assertThat(customerRepository.count()).isEqualTo(customers);
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Ltd");
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId()))
                .extracting(CustomerVersion::getName).containsExactly("Acme Ltd");
    }

    @Test
    void inStrictModeAVersionThatCannotBeBuiltFailsTheSave() {
        historyFaults.failSnapshotOf(CustomerHistory.CODE, acme.getId());

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            rename(acme.getId(), "Acme Renamed");
            rename(globex.getId(), "Globex Renamed");
        })).satisfies(e -> assertThat(causes(e)).anyMatch(c -> c instanceof IllegalStateException
                && c.getMessage().contains("Injected mapping failure")));

        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Ltd");
        assertThat(customerRepository.findById(globex.getId()).orElseThrow().getName()).isEqualTo("Globex");
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, acme.getId())).hasSize(1);
        assertThat(historyTestSupport.versionsOf(CustomerVersion.class, globex.getId())).hasSize(1);
    }

    @Test
    void outsideStrictModeARecordThatCannotBeVersionedIsSkippedAndTheRestAreWritten() {
        historyFaults.failSnapshotOf(CustomerHistory.CODE, acme.getId());
        HistoryWriter lenient = new HistoryWriter(clock, currentUser, versionStore, historyFaults, false);

        tx.executeWithoutResult(status -> {
            rename(acme.getId(), "Acme Renamed");
            rename(globex.getId(), "Globex Renamed");
            SessionImplementor session = em.unwrap(SessionImplementor.class);
            // The writer as production has it, over this transaction's own work; the transaction then rolls
            // back, so the registered (strict) writer never runs.
            HistoryWork work = new HistoryWork(historyTransactions, lenient, session.getTransaction());
            work.mark(CustomerHistory.CODE, acme.getId(), HistoryWork.Change.UPDATED);
            work.mark(CustomerHistory.CODE, globex.getId(), HistoryWork.Change.UPDATED);
            lenient.write(session, work);

            assertThat(versionStore.open(CustomerVersion.class, List.of(acme.getId(), globex.getId())))
                    .extracting(CustomerVersion::getName).containsExactly("Acme Ltd", "Globex Renamed");
            status.setRollbackOnly();
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void rename(Long id, String name) {
        Customer c = customerRepository.findById(id).orElseThrow();
        c.setName(name);
        customerRepository.saveAndFlush(c);
    }

    private static List<Throwable> causes(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) chain.add(t);
        return chain;
    }
}
