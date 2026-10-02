package com.geneinvoice.history;

import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Gives every record that has no current version one (§5.7.9). It runs once the schema upgraders have run (they
 * are {@code InitializingBean}s) and before the web server and {@code DataSeeder} start.
 *
 * <p>The first run on a database copies what is already there as it is now, all from one instant: that instant is
 * {@code history_meta.started_at}, and history can be read from its day on. A later run finds only gaps (a record
 * whose capture was skipped), and their history starts from the moment they are found. Instances that start
 * together take turns on the {@code history_meta} row. A failure is logged and startup carries on: saves never
 * need the baseline, only history reads before the gap do.
 */
@Component
public class HistoryBaseline implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(HistoryBaseline.class);

    @PersistenceContext
    private EntityManager em;

    private final HistoryTransactions transactions;
    private final HistoryTracker tracker;
    private final Clock clock;
    private final TransactionTemplate held;
    private final TransactionTemplate own;

    public HistoryBaseline(HistoryTransactions transactions, HistoryTracker tracker, Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.transactions = transactions;
        this.tracker = tracker;
        this.clock = clock;
        this.held = new TransactionTemplate(transactionManager);
        // Each chunk, and the first insert of the meta row, commit on their own.
        this.own = new TransactionTemplate(transactionManager);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("history.baseline.failed: history of records without a current version starts when they "
                    + "next change", e);
        }
    }

    /** Versions every record that has no current version; returns how many it versioned. */
    public int run() {
        return held.execute(status -> {
            HistoryMeta meta = lockMeta();
            boolean first = meta.getBaselineCompletedAt() == null;
            Instant[] startedAt = {null};
            int copied = 0;
            for (TypeHistory<?, ?> history : HistoryModel.types()) copied += copy(history, first, startedAt);
            if (first) {
                if (startedAt[0] == null && meta.getStartedAt() == null) startedAt[0] = earliestVersion();
                if (startedAt[0] != null) meta.setStartedAt(startedAt[0]);
                meta.setBaselineCompletedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
                log.info("history.baseline copied={} startedAt={}", copied, meta.getStartedAt());
            } else if (copied > 0) {
                log.warn("history.baseline.gaps {} records had no current version; their history starts now", copied);
            }
            return copied;
        });
    }

    /**
     * When the earliest version begins, or null when there is none. A first run finds versions without having copied
     * anything when an earlier first run committed its chunks and then failed before it set {@code started_at}:
     * history starts where those versions do. Read in the held transaction, as the meta row it goes into is locked.
     */
    private Instant earliestVersion() {
        Instant earliest = null;
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            Instant from = em.createQuery("select min(v.validFrom) from " + entityName(history.versionType()) + " v",
                    Instant.class).getSingleResult();
            if (from != null && (earliest == null || from.isBefore(earliest))) earliest = from;
        }
        return earliest;
    }

    /** The meta row, locked for the rest of the run; made first when this database has none. */
    private HistoryMeta lockMeta() {
        HistoryMeta meta = em.find(HistoryMeta.class, HistoryMeta.ID, LockModeType.PESSIMISTIC_WRITE);
        if (meta != null) return meta;
        try {
            own.executeWithoutResult(status -> {
                em.persist(new HistoryMeta(HistoryMeta.ID));
                em.flush();
            });
        } catch (DataIntegrityViolationException | PersistenceException e) {
            log.info("history.baseline: another instance made the meta row first");
        }
        meta = em.find(HistoryMeta.class, HistoryMeta.ID, LockModeType.PESSIMISTIC_WRITE);
        if (meta == null) throw new IllegalStateException("history_meta has no row " + HistoryMeta.ID);
        return meta;
    }

    /**
     * The records of one type without a current version, in chunks of {@value VersionStore#CHUNK} by id, each
     * versioned in a transaction of its own under {@link WriteOrigin#schema()}: on a first run from the one
     * instant the whole baseline shares, otherwise from now.
     */
    private int copy(TypeHistory<?, ?> history, boolean first, Instant[] startedAt) {
        String live = entityName(history.liveType());
        String version = entityName(history.versionType());
        int copied = 0;
        Long last = 0L;
        while (true) {
            Long after = last;
            List<Long> ids = WriteOrigins.callWith(WriteOrigin.schema(), () -> own.execute(status -> {
                List<Long> found = em.createQuery("select e.id from " + live + " e where e.id > :last"
                                + " and not exists (select 1 from " + version + " v where v.id = e.id"
                                + " and v.validTo = :open) order by e.id", Long.class)
                        .setParameter("last", after)
                        .setParameter("open", VersionRow.OPEN)
                        .setMaxResults(VersionStore.CHUNK)
                        .getResultList();
                if (found.isEmpty()) return found;
                if (first && startedAt[0] == null) startedAt[0] = clock.instant().truncatedTo(ChronoUnit.MICROS);
                transactions.current(em).pin(first ? startedAt[0] : null);
                tracker.touch(history.liveType(), found, false);
                return found;
            }));
            if (ids.isEmpty()) return copied;
            copied += ids.size();
            last = ids.get(ids.size() - 1);
        }
    }

    private String entityName(Class<?> type) {
        return em.getMetamodel().entity(type).getName();
    }
}
