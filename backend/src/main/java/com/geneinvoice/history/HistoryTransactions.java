package com.geneinvoice.history;

import jakarta.persistence.EntityManager;
import org.hibernate.Transaction;
import org.hibernate.action.spi.AfterTransactionCompletionProcess;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.ActionQueue;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link HistoryWork} of every Hibernate transaction in progress. A session reuses one {@link Transaction}
 * object for each transaction it runs; the work is removed when that transaction completes, so the next one
 * gets a fresh work, with its own {@code txId}.
 */
@Component
public class HistoryTransactions {

    private final Map<Transaction, HistoryWork> works = new ConcurrentHashMap<>();
    private final HistoryWriter writer;

    public HistoryTransactions(HistoryWriter writer) {
        this.writer = writer;
    }

    /** This transaction's work, made and registered with the session's action queue on first use. */
    public HistoryWork current(SharedSessionContractImplementor session) {
        Transaction tx = session.accessTransaction();
        HistoryWork work = works.get(tx);
        if (work == null) {
            work = new HistoryWork(this, writer, tx);
            works.put(tx, work);
            ActionQueue queue = ((SessionImplementor) session).getActionQueue();
            queue.registerProcess((BeforeTransactionCompletionProcess) work);
            queue.registerProcess((AfterTransactionCompletionProcess) work);
        }
        return work;
    }

    /** A Hibernate session is both, so callers holding one pass it as the session. */
    public HistoryWork current(EntityManager em) {
        return current((SharedSessionContractImplementor) em.unwrap(SessionImplementor.class));
    }

    /**
     * This transaction's history id, for the automation outbox: the writer then records the instant it binds for
     * the transaction in {@code history_transactions}, even when nothing versioned changed (D10). The instant is
     * not bound here; it is bound by the writer once every row lock is held.
     */
    public String txId(EntityManager em) {
        HistoryWork work = current(em);
        work.needInstant();
        return work.txId();
    }

    boolean isRegistered(SharedSessionContractImplementor session, HistoryWork work) {
        return works.get(session.accessTransaction()) == work;
    }

    void completed(HistoryWork work) {
        works.remove(work.transaction(), work);
    }
}
