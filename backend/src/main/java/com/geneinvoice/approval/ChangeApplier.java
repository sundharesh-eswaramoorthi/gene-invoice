package com.geneinvoice.approval;

import java.util.Set;

/**
 * Applies an approved change of the operations it names by re-running the domain's gated method as the maker
 * (§5.11.6). One per domain, in the domain's own package, so the approval package never reaches into services.
 */
public interface ChangeApplier {

    Set<ApprovalOperation> operations();

    /** The domain rows, in the canonical lock order (§5.11.5), before the pending row itself is locked. */
    void lockDomainRows(PendingChange pc);

    /** The record's region now, read under those locks; a change whose record moved fails. */
    Long currentRegionId(PendingChange pc);

    /** Checks the record against what the maker saw, then re-runs the method. Runs as the maker. */
    Outcome<?> apply(PendingChange pc);

    /**
     * The record as it is now, in the same shape as the change's stored {@code base}, or null when it is gone.
     * Read live; the caller decides whether the viewer may see it.
     */
    Object currentView(PendingChange pc);

    Long resultEntityId(Object applied);

    Object resultView(Object applied);
}
