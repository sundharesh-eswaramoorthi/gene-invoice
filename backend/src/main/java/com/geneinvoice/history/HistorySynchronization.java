package com.geneinvoice.history;

import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The moment the mirror is written: the last thing that happens inside the user's transaction,
 * over a fully flushed persistence context (B3).
 *
 * <p>REGISTERED AT TRANSACTION START by {@code config/HistoryTransactionManager}, not lazily by
 * the listener, and that is the non-obvious half of the whole write path. Spring's
 * {@code AbstractPlatformTransactionManager.processCommit} triggers beforeCommit BEFORE
 * {@code doCommit} flushes the EntityManager, so a transaction whose only change is a
 * dirty-checked update — {@code CustomerService.update}: findById, setters, save→merge — has
 * fired no Hibernate event at all by the time a lazily-registered synchronization would have had
 * to register itself. It would silently mirror nothing (B3).
 *
 * <p>LOWEST_PRECEDENCE, against {@code ChangeFeed.Publication}'s LOWEST_PRECEDENCE - 100.
 * {@code TransactionSynchronizationManager.getSynchronizations()} sorts with OrderComparator, so
 * the order is decided here and not by who registered first: Part A's outbox insert happens
 * first, this flush then picks it up, the listener filters it out as unmirrored, and the drain
 * runs last over a context that nothing else is going to change (A5, B3 INTEGRATION).
 */
public final class HistorySynchronization implements TransactionSynchronization {

    /**
     * The current transaction's buffer, or null when there is none — a read-only transaction, a
     * propagation that reused an outer synchronization scope, or no transaction at all.
     *
     * <p>Found by walking the synchronization list rather than by a bound resource, the idiom
     * {@code ChangeFeed.publication()} already establishes and for the same reason: the list is
     * suspended and resumed WITH the transaction, so a REQUIRES_NEW bulk row reaches its own
     * buffer and not its caller's (A5, B3 INTEGRATION).
     */
    static HistoryBuffer bufferOrNull() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (s instanceof HistorySynchronization h) return h.buffer;
        }
        return null;
    }

    private final HistoryBuffer buffer = new HistoryBuffer();
    private final HistoryWriter writer;
    private final PersistenceExceptionTranslator translator;

    public HistorySynchronization(HistoryWriter writer, PersistenceExceptionTranslator translator) {
        this.writer = writer;
        this.translator = translator;
    }

    HistoryBuffer buffer() {
        return buffer;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
        if (readOnly) return;
        // A PROPAGATION_SUPPORTS scope with nothing behind it is synchronised but has no
        // transaction and therefore no EntityManager to flush (B3).
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        flushTranslating();
        writer.drain(buffer);
    }

    /**
     * Fires the listeners, and translates what the flush throws.
     *
     * <p>The translation is not decoration. Until this bean existed, a constraint violation
     * surfaced inside {@code JpaTransactionManager.doCommit}, which runs it through the
     * {@code HibernateJpaDialect} and hands the caller a {@code DataIntegrityViolationException}.
     * Flushing one step earlier would otherwise hand the same caller a raw
     * {@code jakarta.persistence.PersistenceException} instead, changing the exception type of
     * every deferred constraint in the application for a reason that has nothing to do with
     * history (B3).
     */
    private void flushTranslating() {
        try {
            writer.flush();
        } catch (RuntimeException e) {
            DataAccessException translated =
                    translator == null ? null : translator.translateExceptionIfPossible(e);
            throw translated == null ? e : translated;
        }
    }

    @Override
    public void afterCompletion(int status) {
        buffer.clear();
    }
}
