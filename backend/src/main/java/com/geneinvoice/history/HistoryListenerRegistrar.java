package com.geneinvoice.history;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Hooks {@link HistoryEventListener} onto the six Hibernate events the mirror is driven by (B3).
 *
 * <p>The repository's own {@code InitializingBean} + {@code EntityManagerFactory} idiom, for the
 * same reason every {@code *SchemaUpgrade} uses it: the parameter is what orders this bean after
 * Hibernate has built its SessionFactory. Unlike those beans this one DOES throw — a listener
 * that failed to register would leave every mirror silently empty while the application reported
 * itself started, and the first anybody would know is an as-of read answering nothing (B3).
 */
@Component
@Slf4j
public class HistoryListenerRegistrar implements InitializingBean {

    private final EntityManagerFactory entityManagerFactory;
    private final HistoryEventListener listener;

    HistoryListenerRegistrar(EntityManagerFactory entityManagerFactory, HistoryEventListener listener) {
        this.entityManagerFactory = entityManagerFactory;
        this.listener = listener;
    }

    @Override
    public void afterPropertiesSet() {
        EventListenerRegistry registry = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(EventListenerRegistry.class);
        // APPEND and never setListeners: Hibernate's own POST_* listeners maintain the second
        // level cache and the collection action queue, and replacing them would break far more
        // than history (B3).
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
        registry.appendListeners(EventType.POST_COLLECTION_UPDATE, listener);
        registry.appendListeners(EventType.POST_COLLECTION_RECREATE, listener);
        registry.appendListeners(EventType.PRE_COLLECTION_REMOVE, listener);
        log.info("History mirror listener registered on six Hibernate events (B3)");
    }
}
