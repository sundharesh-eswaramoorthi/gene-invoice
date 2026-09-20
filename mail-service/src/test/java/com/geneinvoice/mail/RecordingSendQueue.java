package com.geneinvoice.mail;

import com.geneinvoice.mail.message.SendQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The send queue of {@code mail.queue: direct}: records what was put on it, and a test runs the worker
 * for it. Put on the queue fails while {@link #failing} is set, as when RabbitMQ is down.
 */
public final class RecordingSendQueue implements SendQueue {

    public record Retry(long id, Duration delay) {}

    private final List<Long> enqueued = new CopyOnWriteArrayList<>();
    private final List<Retry> retries = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void enqueue(long id) {
        if (failing) throw new IllegalStateException("RabbitMQ is not reachable");
        enqueued.add(id);
    }

    @Override
    public void enqueueRetry(long id, Duration delay) {
        if (failing) throw new IllegalStateException("RabbitMQ is not reachable");
        retries.add(new Retry(id, delay));
    }

    public List<Long> enqueued() {
        return List.copyOf(enqueued);
    }

    public List<Retry> retries() {
        return List.copyOf(retries);
    }

    /** What was put on the queue since the last call, in order. */
    public List<Long> take() {
        List<Long> taken = new ArrayList<>(enqueued);
        enqueued.removeAll(taken);
        return taken;
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        enqueued.clear();
        retries.clear();
        failing = false;
    }
}
