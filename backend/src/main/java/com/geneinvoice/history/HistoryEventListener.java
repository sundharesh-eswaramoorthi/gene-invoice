package com.geneinvoice.history;

import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.hibernate.event.spi.AbstractCollectionEvent;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.event.spi.PreCollectionRemoveEvent;
import org.hibernate.event.spi.PreCollectionRemoveEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The trigger of the mirror: Hibernate says what it wrote, this writes down the key (B3).
 *
 * <p>IT RECORDS KEYS ONLY. It never serialises a row, never runs a query, never reads an
 * association and never throws. That is not tidiness — this code runs inside Hibernate's flush,
 * under the user's transaction, on the path of every save in the application, and anything it
 * does wrong becomes a failed invoice. The snapshot is taken afterwards, once, from the final
 * flushed state, by {@link HistoryWriter}.
 *
 * <p>IT FILTERS ON {@code registry.isMirrored} FIRST, and that is the blueprint's resolution of
 * the ChangeFeed interaction: Part A writes {@code automation_events} rows inside its own
 * beforeCommit, which fires POST_INSERT, and a listener that buffered first and filtered later
 * would enter a key AFTER the drain had already walked the buffer — a key that is then never
 * written and that nothing but the fifteen-minute reconciler would ever notice (A5, B3
 * INTEGRATION).
 *
 * <p>THE THREE COLLECTION EVENTS ARE FOR THE TWO JOIN TABLES AND FOR NOTHING ELSE. A
 * {@code @ManyToMany} join table has no entity, so no insert, update or delete event is ever
 * raised for {@code payment_promise_invoices} or {@code payment_promise_payments}; a collection
 * event on the owning promise is the only signal there is. Every other collection in the model
 * (an invoice's items, a task's assignees) is made of real entities that raise their own events,
 * which is why the filter here is "does this owner own a link mirror" and not "is this owner
 * mirrored" (B3).
 */
@Component
@RequiredArgsConstructor
public class HistoryEventListener implements PostInsertEventListener, PostUpdateEventListener,
        PostDeleteEventListener, PostCollectionUpdateEventListener,
        PostCollectionRecreateEventListener, PreCollectionRemoveEventListener {

    private final HistoryRegistry registry;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(event.getPersister(), event.getId());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        record(event.getPersister(), event.getId());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        // A delete is a key like any other. The drain finds nothing behind it and writes a
        // tombstone, which is how a record stays readable as of a date before it went (B3).
        record(event.getPersister(), event.getId());
    }

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        recordLinks(event);
    }

    @Override
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        recordLinks(event);
    }

    @Override
    public void onPreRemoveCollection(PreCollectionRemoveEvent event) {
        // PRE and not POST, because POST_COLLECTION_REMOVE is not raised at all when the owner
        // itself is being deleted; the drain reads the join table after the flush either way, so
        // seeing the key one statement early costs nothing and losing it would leave a link open
        // for ever (B3).
        recordLinks(event);
    }

    /**
     * False, and deliberately: post-commit handling would run the callback AFTER the transaction
     * had committed, which is the one place a mirror row could not be written atomically with the
     * record it is a version of (B3).
     */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void record(EntityPersister persister, Object id) {
        // Off the persister and never off entity.getClass(): a lazily-loaded entity is a proxy
        // subclass, and HistoryRegistry is keyed by the mapped class (B3).
        if (persister == null) return;
        Class<?> type = persister.getMappedClass();
        if (!registry.isMirrored(type)) return;
        touch(type, id, HistoryBuffer.Part.ROW);
    }

    private void recordLinks(AbstractCollectionEvent event) {
        Object owner = event.getAffectedOwnerOrNull();
        if (owner == null) return;
        Class<?> type = Hibernate.getClass(owner);
        if (registry.linksOf(type).isEmpty()) return;
        touch(type, event.getAffectedOwnerIdOrNull(), HistoryBuffer.Part.LINKS);
    }

    private void touch(Class<?> type, Object id, HistoryBuffer.Part part) {
        if (!(id instanceof Number number)) return;
        HistoryBuffer buffer = buffer();
        // Null outside a synchronised transaction — a raw SessionFactory session in a tool, or a
        // read-only transaction, neither of which the mirror is written from. Silent rather than
        // loud, because the reconciler is the guarantee and a log line per row on a bulk import
        // would be its own outage (B3).
        if (buffer == null) return;
        buffer.touch(type, number.longValue(), part);
    }

    private HistoryBuffer buffer() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        return HistorySynchronization.bufferOrNull();
    }
}
