package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.history.HistoryWork.Change;
import com.geneinvoice.history.types.InvoiceHistory;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceItem;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.AbstractCollectionEvent;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostCollectionRemoveEvent;
import org.hibernate.event.spi.PostCollectionRemoveEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notes which records a transaction changes, as keys for its {@link HistoryWork}; the versions themselves are
 * written just before commit. It does no database work. Version rows and records without history are ignored.
 */
public class HistoryListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener,
        PostCollectionRecreateEventListener, PostCollectionUpdateEventListener, PostCollectionRemoveEventListener {

    /** A key a collection change marks on its owner. */
    private record Mark(String code, Change change) {}

    private final HistoryTransactions transactions;
    /**
     * Owner entity name → what a change to one of its collections marks. By owner, not by role: the event's
     * collection wrapper has no role any more once its action ran. An invoice's only collection is its lines; a
     * promise's are its links, and a change to either re-reads both (one query). A payment's allocations are
     * entities of their own, so their events cover them.
     */
    private final Map<String, List<Mark>> byOwner = new HashMap<>();

    public HistoryListener(HistoryTransactions transactions) {
        this.transactions = transactions;
        byOwner.put(Invoice.class.getName(), List.of(new Mark(InvoiceHistory.CODE, Change.CHILD)));
        for (LinkHistory link : HistoryModel.links()) {
            byOwner.computeIfAbsent(link.owner().getName(), o -> new ArrayList<>())
                    .add(new Mark(link.code(), Change.UPDATED));
        }
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        entity(event.getEntity(), event.getId(), event.getPersister(), event.getSession(), Change.INSERTED);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        entity(event.getEntity(), event.getId(), event.getPersister(), event.getSession(), Change.UPDATED);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        entity(event.getEntity(), event.getId(), event.getPersister(), event.getSession(), Change.DELETED);
    }

    @Override
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        collection(event);
    }

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        collection(event);
    }

    @Override
    public void onPostRemoveCollection(PostCollectionRemoveEvent event) {
        collection(event);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void entity(Object entity, Object id, EntityPersister persister, EventSource session, Change change) {
        Class<?> type = persister.getMappedClass();
        if (type == InvoiceItem.class) {
            // A line is part of its invoice's version: the invoice is marked, not the line.
            Long invoiceId = idOf(((InvoiceItem) entity).getInvoice());
            if (invoiceId != null) mark(session, InvoiceHistory.CODE, invoiceId, Change.CHILD);
            return;
        }
        if (!HistoryModel.isVersioned(type)) return;
        mark(session, HistoryModel.of(type).code(), (Long) id, change);
    }

    private void collection(AbstractCollectionEvent event) {
        List<Mark> marks = byOwner.get(event.getAffectedOwnerEntityName());
        if (marks == null) return;
        if (event.getAffectedOwnerIdOrNull() instanceof Long id) {
            for (Mark m : marks) mark(event.getSession(), m.code(), id, m.change());
        }
    }

    private void mark(EventSource session, String code, Long id, Change change) {
        if (AsOf.active()) throw AsOfException.readOnly(AsOf.current().date());
        transactions.current((SharedSessionContractImplementor) session).mark(code, id, change);
    }

    /** An invoice's id, read from a lazy proxy without loading it. */
    private static Long idOf(Invoice invoice) {
        if (invoice == null) return null;
        if (invoice instanceof HibernateProxy proxy) {
            return (Long) proxy.getHibernateLazyInitializer().getInternalIdentifier();
        }
        return invoice.getId();
    }
}
