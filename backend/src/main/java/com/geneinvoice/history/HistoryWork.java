package com.geneinvoice.history;

import org.hibernate.Transaction;
import org.hibernate.action.spi.AfterTransactionCompletionProcess;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The records one Hibernate transaction touched, turned into versions just before it commits. Registered with
 * the session's action queue by {@link HistoryTransactions} on first use.
 *
 * <p>Hibernate keeps a before-completion process that never ran (the transaction rolled back) in the queue, and
 * a session that outlives its transaction (open-in-view) runs it at its next commit. So the work marks itself
 * done after completion either way, and writes only while it is the registered work of the transaction that is
 * committing.
 */
public class HistoryWork implements BeforeTransactionCompletionProcess, AfterTransactionCompletionProcess {

    private static final Logger log = LoggerFactory.getLogger(HistoryWork.class);

    /** What happened to a record in this transaction. */
    public enum Change {
        INSERTED, UPDATED, DELETED,
        /** A child row changed (an invoice's line): the root's snapshot may differ though its row was not written. */
        CHILD,
        /** Named by {@link HistoryTracker#touch}: changed by SQL the listener never saw. */
        TOUCHED
    }

    private final HistoryTransactions transactions;
    private final HistoryWriter writer;
    private final Transaction transaction;
    private final String txId = UUID.randomUUID().toString();
    /** Type code (or link code) → record id → what happened to it. */
    private final Map<String, SortedMap<Long, Set<Change>>> touched = new LinkedHashMap<>();
    /** {@code "CODE id"} of every record (or link owner) whose versions the writer changed. */
    private final Set<String> written = new HashSet<>();
    private Instant instant;
    private Instant pinned;
    private boolean writing;
    /** The outbox took this transaction's id: its instant is recorded even if nothing versioned changed. */
    private boolean needsInstant;
    private boolean done;

    HistoryWork(HistoryTransactions transactions, HistoryWriter writer, Transaction transaction) {
        this.transactions = transactions;
        this.writer = writer;
        this.transaction = transaction;
    }

    public String txId() {
        return txId;
    }

    Transaction transaction() {
        return transaction;
    }

    /** Records that {@code (code, id)} changed. Ignored once the writer has started: its own writes are not news. */
    public void mark(String code, Long id, Change change) {
        if (id == null) return;
        if (writing) {
            log.error("history.late.change {} {} {}: a tracked record changed while its versions were written",
                    code, id, change);
            return;
        }
        touched.computeIfAbsent(code, c -> new TreeMap<>())
                .computeIfAbsent(id, i -> EnumSet.noneOf(Change.class))
                .add(change);
    }

    /** For a baseline: every version this work writes starts at {@code at} instead of the commit instant. */
    public void pin(Instant at) {
        this.pinned = at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    boolean isPinned() {
        return pinned != null;
    }

    boolean isEmpty() {
        return touched.isEmpty();
    }

    void needInstant() {
        needsInstant = true;
    }

    boolean needsInstant() {
        return needsInstant;
    }

    /** The touched records of one code, ids ascending; empty when there are none. */
    SortedMap<Long, Set<Change>> touched(String code) {
        return touched.getOrDefault(code, Collections.emptySortedMap());
    }

    /** The writer opened or closed a version of {@code (code, id)}. */
    void written(String code, Long id) {
        written.add(code + " " + id);
    }

    /** The records whose versions this work changed, as {@code "CODE id"}; complete once the transaction committed. */
    public Set<String> written() {
        return Set.copyOf(written);
    }

    void startWriting() {
        writing = true;
    }

    /**
     * The transaction's instant, bound on first call: the writer asks only once it holds every row lock the
     * transaction takes, so a later holder of the same row gets a later-or-equal instant.
     */
    Instant instant(Clock clock) {
        if (instant == null) instant = pinned != null ? pinned : clock.instant().truncatedTo(ChronoUnit.MICROS);
        return instant;
    }

    @Override
    public void doBeforeTransactionCompletion(SessionImplementor session) {
        if (done || !transactions.isRegistered(session, this)) return;
        writer.write(session, this);
    }

    @Override
    public void doAfterTransactionCompletion(boolean success, SharedSessionContractImplementor session) {
        done = true;
        transactions.completed(this);
    }
}
