package com.geneinvoice.automation;

import com.geneinvoice.automation.run.AutomationFaults;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tests' {@link AutomationFaults} (the primary bean in {@code IntegrationTestBase.TestBeans}): makes the next
 * actions fail as a real failure would, stops "the process" just after an action committed, runs a test's own code
 * at a point of the lock window, and counts the statements an action runs inside it. Reset before every test.
 */
public class RecordingAutomationFaults implements AutomationFaults {

    /** Stands in for the process stopping: an {@link Error}, which nothing in the consumer catches. */
    public static class SimulatedCrash extends Error {
        public SimulatedCrash() {
            super("Simulated crash after the action committed");
        }
    }

    private final Deque<Supplier<RuntimeException>> beforeAction = new ArrayDeque<>();
    private final Deque<Supplier<RuntimeException>> afterLock = new ArrayDeque<>();
    private final List<Runnable> onBeforeAction = new ArrayList<>();
    private int crashesAfterCommit;
    private SessionFactory counting;
    private long statementsAtLock = -1;
    private final List<Long> lockWindows = new ArrayList<>();
    private final List<String> acted = new ArrayList<>();

    public synchronized void reset() {
        beforeAction.clear();
        afterLock.clear();
        onBeforeAction.clear();
        crashesAfterCommit = 0;
        if (counting != null) counting.getStatistics().setStatisticsEnabled(false);
        counting = null;
        statementsAtLock = -1;
        lockWindows.clear();
        acted.clear();
    }

    /** The next actions fail just before they write, each with one of these, in order. */
    @SafeVarargs
    public final synchronized void failBeforeAction(Supplier<RuntimeException>... failures) {
        for (Supplier<RuntimeException> f : failures) beforeAction.addLast(f);
    }

    /** The next action fails as soon as the customer is locked (a lock timeout, say). */
    public synchronized void failAfterLock(Supplier<RuntimeException> failure) {
        afterLock.addLast(failure);
    }

    /** The next {@code times} actions stop "the process" just after they committed. */
    public synchronized void crashAfterCommit(int times) {
        crashesAfterCommit = times;
    }

    /** {@code action} runs on the worker's thread just before the next write (inside its lock window). */
    public synchronized void onNextBeforeAction(Runnable action) {
        onBeforeAction.add(action);
    }

    /** From now on, counts the statements each action runs from the lock to its commit. */
    public synchronized void countLockWindows(SessionFactory sessionFactory) {
        counting = sessionFactory;
        sessionFactory.getStatistics().setStatisticsEnabled(true);
    }

    public synchronized List<Long> lockWindows() {
        return List.copyOf(lockWindows);
    }

    /** "ruleId:actionKey:TYPE:id" of every action that committed. */
    public synchronized List<String> acted() {
        return List.copyOf(acted);
    }

    @Override
    public void afterLock(Long ruleId, String actionKey, String targetType, Long targetId) {
        Supplier<RuntimeException> failure;
        synchronized (this) {
            if (counting != null) statementsAtLock = statements();
            failure = afterLock.pollFirst();
        }
        if (failure != null) throw failure.get();
    }

    @Override
    public void beforeAction(Long ruleId, String actionKey, String targetType, Long targetId) {
        Supplier<RuntimeException> failure;
        List<Runnable> run;
        synchronized (this) {
            failure = beforeAction.pollFirst();
            run = List.copyOf(onBeforeAction);
            onBeforeAction.clear();
        }
        run.forEach(Runnable::run);
        if (failure != null) throw failure.get();
    }

    @Override
    public void afterActionCommitted(Long ruleId, String actionKey, String targetType, Long targetId) {
        boolean crash;
        synchronized (this) {
            if (counting != null && statementsAtLock >= 0) {
                lockWindows.add(statements() - statementsAtLock);
                statementsAtLock = -1;
            }
            acted.add(ruleId + ":" + actionKey + ":" + targetType + ":" + targetId);
            crash = crashesAfterCommit > 0;
            if (crash) crashesAfterCommit--;
        }
        if (crash) throw new SimulatedCrash();
    }

    private long statements() {
        Statistics stats = counting.getStatistics();
        return stats.getPrepareStatementCount();
    }
}
