package com.geneinvoice.mail.message;

import java.time.Duration;

/**
 * Where copies wait for a worker. A message carries only the copy's id; the row is the truth, so a
 * message delivered twice or lost costs nothing (the claim, the sweeper).
 */
public interface SendQueue {

    /** A queue message: {@code {"id": 123}}. */
    record Item(long id) {}

    /** For a worker as soon as one is free. */
    void enqueue(long id);

    /** For a worker once {@code delay} has passed: the first retry delay waits in one queue, any longer in the other. */
    void enqueueRetry(long id, Duration delay);
}
