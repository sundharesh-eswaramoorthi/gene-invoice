package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.history.HistoryWork.Change;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The only sanctioned way to version rows changed by bulk JPQL or native SQL, which the listener never sees
 * (a region move, the baseline, the reconciler): name them here, in the same transaction, after the SQL ran.
 */
@Component
public class HistoryTracker {

    @PersistenceContext
    private EntityManager em;

    private final HistoryTransactions transactions;

    public HistoryTracker(HistoryTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * Versions {@code ids} of {@code live} (and the links they own) at this transaction's commit. A copy this
     * session already holds is re-read first, so the version is taken from what the SQL wrote, and let go when the
     * SQL deleted its row. With {@code lock}, each row is locked before it is read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(Class<?> live, Collection<Long> ids, boolean lock) {
        if (AsOf.active()) throw AsOfException.readOnly(AsOf.current().date());
        TypeHistory<?, ?> history = HistoryModel.of(live);
        em.flush();
        SessionImplementor session = em.unwrap(SessionImplementor.class);
        EntityPersister persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(live);
        HistoryWork work = transactions.current((SharedSessionContractImplementor) session);
        List<Long> sorted = new ArrayList<>(new TreeSet<>(ids.stream().filter(Objects::nonNull).toList()));
        Set<Long> existing = existing(live, sorted);
        for (Long id : sorted) {
            Object held = session.getPersistenceContextInternal().getEntity(session.generateEntityKey(id, persister));
            if (!existing.contains(id)) {
                // Refreshing a row that is gone would fail the whole transaction, not just this call.
                if (held != null) em.detach(held);
            } else if (held != null) {
                em.refresh(held, lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
            } else if (lock) {
                em.find(live, id, LockModeType.PESSIMISTIC_WRITE);
            }
            work.mark(history.code(), id, Change.TOUCHED);
            for (LinkHistory link : HistoryModel.linksOf(live)) work.mark(link.code(), id, Change.TOUCHED);
        }
    }

    /** Which of {@code ids} still have a row, one query per chunk. */
    private Set<Long> existing(Class<?> live, List<Long> ids) {
        Set<Long> existing = new HashSet<>();
        String name = em.getMetamodel().entity(live).getName();
        for (int from = 0; from < ids.size(); from += VersionStore.CHUNK) {
            existing.addAll(em.createQuery("select e.id from " + name + " e where e.id in :ids", Long.class)
                    .setParameter("ids", ids.subList(from, Math.min(ids.size(), from + VersionStore.CHUNK)))
                    .getResultList());
        }
        return existing;
    }
}
