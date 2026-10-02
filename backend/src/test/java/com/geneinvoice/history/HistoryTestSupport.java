package com.geneinvoice.history;

import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.HistoryTransactionRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What the tests need of history: an empty history before every test, reading versions back, and versioning
 * rows a test changed with SQL (as a region move or the baseline would, through {@link HistoryTracker}).
 * A bean of {@code IntegrationTestBase.TestBeans}.
 */
public class HistoryTestSupport {

    @PersistenceContext
    private EntityManager em;

    @Autowired private TransactionTemplate tx;
    @Autowired private HistoryTracker tracker;

    /** Every version table, the link versions, the history meta row and the transaction instants, emptied at once. */
    public void clearVersions() {
        tx.executeWithoutResult(status -> {
            List<Class<?>> tables = new ArrayList<>();
            HistoryModel.types().forEach(t -> tables.add(t.versionType()));
            tables.add(EntityLinkVersion.class);
            tables.add(HistoryMeta.class);
            tables.add(HistoryTransactionRow.class);
            for (Class<?> table : tables) {
                em.createQuery("delete from " + em.getMetamodel().entity(table).getName()).executeUpdate();
            }
        });
    }

    /** Every version of one record, oldest first. */
    public <V extends VersionRow> List<V> versionsOf(Class<V> type, Long id) {
        return tx.execute(status -> em.createQuery("select v from " + em.getMetamodel().entity(type).getName()
                        + " v where v.id = :id order by v.validFrom, v.versionId", type)
                .setParameter("id", id)
                .getResultList());
    }

    /** Every version of a table, oldest first. */
    public <V extends VersionRow> List<V> allVersions(Class<V> type) {
        return tx.execute(status -> em.createQuery("select v from " + em.getMetamodel().entity(type).getName()
                        + " v order by v.id, v.validFrom, v.versionId", type)
                .getResultList());
    }

    /** Link rows of one kind and owner, oldest first. */
    public List<EntityLinkVersion> linksOf(String kind, Long ownerId) {
        return tx.execute(status -> em.createQuery("""
                        select l from EntityLinkVersion l where l.kind = :kind and l.ownerId = :owner
                         order by l.validFrom, l.id, l.versionId""",
                        EntityLinkVersion.class)
                .setParameter("kind", kind)
                .setParameter("owner", ownerId)
                .getResultList());
    }

    /** Versions rows a test changed with SQL, as the sanctioned bulk paths do. */
    public void touch(Class<?> live, Collection<Long> ids) {
        tx.executeWithoutResult(status -> tracker.touch(live, ids, false));
    }
}
