package com.geneinvoice.history;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.stereotype.Component;

/**
 * Puts the {@link HistoryListener} on the session factory, for every entity and collection event it maps, and the
 * {@link LiveReadGuard} on every load.
 */
@Component
public class HistoryListenerRegistrar {

    private final EntityManagerFactory emf;
    private final HistoryTransactions transactions;

    public HistoryListenerRegistrar(EntityManagerFactory emf, HistoryTransactions transactions) {
        this.emf = emf;
        this.transactions = transactions;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        HistoryListener listener = new HistoryListener(transactions);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
        registry.appendListeners(EventType.POST_COLLECTION_RECREATE, listener);
        registry.appendListeners(EventType.POST_COLLECTION_UPDATE, listener);
        registry.appendListeners(EventType.POST_COLLECTION_REMOVE, listener);
        registry.appendListeners(EventType.POST_LOAD, new LiveReadGuard());
    }
}
