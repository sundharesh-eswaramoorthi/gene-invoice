package com.geneinvoice.history;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The tests' {@link HistoryFaults} (the primary bean in {@code IntegrationTestBase.TestBeans}): makes the
 * snapshot of chosen records fail as a mapping error would, or tells a test when a writer is about to take one or
 * is between its two reads of a link owner. Reset before every test.
 */
public class HistoryTestHooks implements HistoryFaults {

    private final Set<String> failing = new HashSet<>();
    private final Map<String, Runnable> onNext = new HashMap<>();

    /** The next snapshots of {@code (code, id)} fail until {@link #reset()}. */
    public synchronized void failSnapshotOf(String code, Long id) {
        failing.add(code + " " + id);
    }

    /** {@code action} runs once, on the writer's thread, just before the next snapshot of {@code (code, id)}. */
    public synchronized void onNextSnapshotOf(String code, Long id, Runnable action) {
        onNext.put(code + " " + id, action);
    }

    /**
     * {@code action} runs once, on the writer's thread, when the next writer of the link kind {@code code} has read
     * the owner's targets and not yet its open rows.
     */
    public synchronized void onNextLinkReadsOf(String code, Long ownerId, Runnable action) {
        onNext.put("links " + code + " " + ownerId, action);
    }

    public synchronized void reset() {
        failing.clear();
        onNext.clear();
    }

    @Override
    public void beforeSnapshot(String code, Long id) {
        Runnable action;
        synchronized (this) {
            if (failing.contains(code + " " + id)) {
                throw new IllegalStateException("Injected mapping failure for " + code + " " + id);
            }
            action = onNext.remove(code + " " + id);
        }
        if (action != null) action.run();
    }

    @Override
    public void betweenLinkReads(String code, Long ownerId) {
        Runnable action;
        synchronized (this) {
            action = onNext.remove("links " + code + " " + ownerId);
        }
        if (action != null) action.run();
    }
}
