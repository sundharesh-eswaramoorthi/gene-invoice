package com.geneinvoice.common;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Notes every entity with a {@code @Lob} that is loaded while no transaction is open. On PostgreSQL such a load fails
 * ("Unable to access lob stream", E2E AUT-R1-1/2); H2 reads it happily, so a test asks this probe instead. It adds no
 * bean (the test context stays the shared one): the listener is appended to Hibernate once and records only while
 * {@link #during} runs.
 */
public final class LobLoadProbe {

    private static final List<String> OUTSIDE = new CopyOnWriteArrayList<>();
    private static volatile boolean recording;
    private static volatile boolean installed;

    private LobLoadProbe() {
    }

    /** What {@code work} loaded with a {@code @Lob} outside a transaction, as "Entity#id". */
    public static List<String> during(EntityManagerFactory emf, Supplier<?> work) {
        install(emf);
        OUTSIDE.clear();
        recording = true;
        try {
            work.get();
        } finally {
            recording = false;
        }
        return new ArrayList<>(OUTSIDE);
    }

    private static synchronized void install(EntityManagerFactory emf) {
        if (installed) return;
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry = sf.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_LOAD, (PostLoadEventListener) event -> {
            if (!recording || TransactionSynchronizationManager.isActualTransactionActive()) return;
            Object entity = event.getEntity();
            if (entity != null && LobReadsAreTransactionalTest.hasLob(entity.getClass())) {
                OUTSIDE.add(entity.getClass().getSimpleName() + "#" + event.getId());
            }
        });
        installed = true;
    }
}
