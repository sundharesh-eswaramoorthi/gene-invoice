package com.geneinvoice.automation;

import java.util.List;

/**
 * Where outbox rows wait for a worker (R5). A message carries nothing but the row's id, because the
 * row is the truth: a message delivered twice costs nothing (the claim wins once), and a message
 * lost costs nothing (the sweeper publishes it again). That is what lets the transport be a seam at
 * all — swapping it changes how quickly work is picked up and never whether it is.
 *
 * <p>{@link InProcessAutomationQueue} is the default and needs no broker, so the compose file and
 * every existing test keep working untouched. It is also, today, the only implementation: a
 * broker-backed one is the same two methods and a value beside them in
 * {@link AutomationQueueCheck#TRANSPORTS}, and until it is written {@code app.automation.queue}
 * takes one value and says so (D-75).
 */
public interface AutomationQueue {

    /**
     * What goes on the wire, for a transport that has one: {@code {"id": 123}} and nothing else.
     * Declared on the seam rather than inside an implementation so that every transport carries the
     * same thing, and so it stays obvious that a message is a pointer at a row and never a copy of
     * the work.
     */
    record Item(long id) {}

    /** For a worker as soon as one is free. Best-effort: a failure here is a log line, never a 500. */
    void enqueue(long eventId);

    /** The same for a batch — a fan-out's rule rows, or a scheduled run's — in one hand-off. */
    void enqueueAll(List<Long> eventIds);
}
