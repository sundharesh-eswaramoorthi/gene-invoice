package com.geneinvoice.config;

import com.geneinvoice.history.HistoryWriter;
import com.geneinvoice.history.HistorySynchronization;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application's transaction manager, which exists for one reason: to register the history
 * synchronization at transaction START rather than leaving it to whoever happens to write
 * something first (B3).
 *
 * <p>THIS IS LOAD-BEARING AND IT IS NOT OBVIOUS. Spring's
 * {@code AbstractPlatformTransactionManager.processCommit} calls {@code triggerBeforeCommit}
 * BEFORE {@code doCommit} flushes the EntityManager. A transaction whose only change is a
 * dirty-checked update — {@code CustomerService.update} is exactly that shape: findById, setters,
 * save→merge, no explicit flush — therefore has fired no Hibernate entity event at all at the
 * moment a lazily-registered synchronization would have had to exist. It would mirror nothing,
 * silently, for every update in the application that does not happen to trigger a flush first.
 * {@code HistoryWriteTest#aDirtyCheckOnlyUpdateWritesAHistoryRow} is the guard on this override
 * and is marked load-bearing in its own comment: if Spring's ordering contract ever shifts, that
 * test is what says so, and without it history would simply stop being written until the
 * reconciler noticed fifteen minutes later.
 *
 * <p>Boot's own manager is {@code @ConditionalOnMissingBean(TransactionManager.class)}, so
 * declaring this one replaces it rather than competing with it. It is named
 * {@code transactionManager} so that anything resolving the conventional name still finds it, and
 * it applies Boot's {@code TransactionManagerCustomizers} so the {@code spring.transaction.*}
 * properties keep working.
 *
 * <p>Registering at transaction start means EVERY synchronised write scope gets one, including
 * B2's park transaction (which writes only {@code pending_changes}, not mirrored, so its drain is
 * a no-op) and every {@code BulkExecutor} REQUIRES_NEW row (which gets its own buffer and its own
 * drain, which is precisely what makes one bulk row's mirror rows commit with that row). Both are
 * benign and both are now required to stay so (A5, B2, B3 INTEGRATION).
 */
@Component("transactionManager")
public class HistoryTransactionManager extends JpaTransactionManager {

    private final ObjectProvider<HistoryWriter> writer;

    public HistoryTransactionManager(EntityManagerFactory entityManagerFactory,
                                     ObjectProvider<HistoryWriter> writer,
                                     ObjectProvider<TransactionManagerCustomizers> customizers) {
        // The one-argument super constructor runs afterPropertiesSet for us, which is what picks
        // the DataSource and the HibernateJpaDialect off the EntityManagerFactory — and the
        // DataSource is what makes the drain's DataSourceUtils.getConnection hand back the very
        // connection Hibernate is flushing on, rather than a second one outside the transaction.
        super(entityManagerFactory);
        this.writer = writer;
        customizers.ifAvailable(c -> c.customize((TransactionManager) this));
    }

    @Override
    protected void prepareSynchronization(DefaultTransactionStatus status,
                                          TransactionDefinition definition) {
        super.prepareSynchronization(status, definition);
        // hasTransaction() excludes a PROPAGATION_SUPPORTS scope with nothing behind it, which is
        // synchronised but has no EntityManager to flush; readOnly excludes every list and report
        // in the application, which cannot change anything and must not pay for a drain (B3).
        if (status.isNewSynchronization() && status.hasTransaction() && !definition.isReadOnly()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new HistorySynchronization(writer.getObject(), getJpaDialect()));
        }
    }
}
