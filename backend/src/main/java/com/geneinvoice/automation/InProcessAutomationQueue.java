package com.geneinvoice.automation;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The default transport (R5): one daemon thread inside the app, in the shape
 * {@code EmailDispatcher} already uses for the same job. No broker, no extra dependency, and a
 * deployment that has never heard of RabbitMQ automates exactly as well — only a restart loses the
 * hand-off, and losing a hand-off is the case the sweeper exists for (R8).
 *
 * <p>One thread on purpose. A rule's action writes to the database and may call the mail service,
 * and a fan-out over a bulk import would otherwise open as many connections at once as the import
 * had rows. Work that queues behind it is late, which is what a rule is allowed to be; what it is
 * never allowed to be is in the way of the user's save, and it is not, because it is not on the
 * user's thread at all.
 *
 * <p>Nothing is tracked in memory about which rows this thread is carrying. It does not need to be:
 * the sweeper leaves alone anything published within its republish window, and the claim settles
 * whatever slips through that. State here would only be a second copy that a restart makes wrong.
 */
@Component
@ConditionalOnProperty(prefix = "app.automation", name = "queue", havingValue = "in-process",
        matchIfMissing = true)
@Slf4j
public class InProcessAutomationQueue implements AutomationQueue {

    /**
     * Resolved when a message is actually run, not when this bean is built. The worker's fan-out
     * enqueues the rule rows it writes, so worker and queue refer to each other; asking for it
     * lazily is what lets both be plain singletons instead of the context failing to start on a
     * circular reference.
     */
    private final ObjectProvider<AutomationWorker> worker;
    /**
     * False runs the work on the calling thread, so a test that drives a run — the sweeper, a
     * scheduled run, Run now — sees the outcome by the time the call returns. It does not apply to
     * the after-commit nudge; see {@link #inSomebodysTransaction()}.
     */
    private final boolean async;
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "automation-run");
        t.setDaemon(true);
        return t;
    });

    public InProcessAutomationQueue(ObjectProvider<AutomationWorker> worker,
                                    @Value("${app.automation.async:true}") boolean async) {
        this.worker = worker;
        this.async = async;
    }

    @PreDestroy
    void shutdown() {
        // Whatever is still queued stays QUEUED in the table, and the sweeper runs it after a restart.
        background.shutdownNow();
    }

    @Override
    public void enqueue(long eventId) {
        enqueueAll(List.of(eventId));
    }

    @Override
    public void enqueueAll(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return;
        List<Long> ids = List.copyOf(eventIds);
        if (!async && !inSomebodysTransaction()) {
            ids.forEach(this::run);
            return;
        }
        try {
            background.execute(() -> ids.forEach(this::run));
        } catch (RejectedExecutionException e) {
            log.warn("Shutting down; {} automation event(s) stay queued for the sweeper", ids.size());
        }
    }

    /**
     * True where running the work on this thread would run it inside somebody's transaction.
     *
     * <p>The nudge that brings most work here is an after-commit callback, and Spring holds the
     * synchronisation open across that callback: the data is committed and visible, but the
     * thread still looks to the transaction manager as though it were inside a transaction, so a
     * REQUIRED template opened here would join a transaction that has already committed and
     * quietly never commit anything it wrote. That is the well-worn way to lose writes from an
     * after-commit hook, and it is why inline mode steps aside for the background thread here
     * instead of taking the work: late is fine, lost is not.
     */
    private static boolean inSomebodysTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive();
    }

    /**
     * One row, with everything swallowed. The worker settles on the row itself every failure it can
     * describe; what escapes it must not kill the thread, or every row behind this one waits for a
     * restart.
     */
    private void run(Long eventId) {
        try {
            worker.getObject().process(eventId);
        } catch (RuntimeException e) {
            log.warn("Automation event {} could not be run; the sweeper will pick it up", eventId, e);
        }
    }
}
