package com.geneinvoice.automation.run;

/**
 * A seam for tests to make an action fail, or the process stop, where a real failure would (§5.10.6): the executor
 * calls it at the start of the lock window, just before the write, and just after the write committed. The
 * production bean ({@link NoAutomationFaults}) does nothing.
 */
public interface AutomationFaults {

    /** The customer is locked for the action on {@code (type, id)}: the lock window starts. */
    void afterLock(Long ruleId, String actionKey, String targetType, Long targetId);

    /** Just before the action writes; throwing stands in for a failure of the write. */
    void beforeAction(Long ruleId, String actionKey, String targetType, Long targetId);

    /** The action and its effect row committed; throwing an {@link Error} stands in for the process stopping. */
    void afterActionCommitted(Long ruleId, String actionKey, String targetType, Long targetId);
}
