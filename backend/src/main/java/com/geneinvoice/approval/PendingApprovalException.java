package com.geneinvoice.approval;

/**
 * Control flow, not a failure. The gate throws this out of a money mutator so Spring rolls the
 * mutator's transaction back — and that rollback IS what "the save does not take effect" means.
 * The row it carries is UNSAVED on purpose: writing it from inside the doomed transaction would
 * write it and then undo it, so whoever catches this writes it in a transaction of its own (B2).
 *
 * <p>It extends RuntimeException and deliberately NOT BadRequestException.
 * {@code BulkExecutor.eligibility} (common/bulk/BulkExecutor.java:38-46) turns every
 * BadRequestException into an IneligibleException, so a held row inside a bulk run would be
 * reported as "skipped, did not qualify" — which is the one sentence that says the opposite of
 * what actually happened to it (B2).
 */
public class PendingApprovalException extends RuntimeException {

    private final transient PendingChange change;

    public PendingApprovalException(PendingChange change) {
        // No stack trace: this is thrown on a normal path, once per held save, and the trace is
        // never read by anybody (B2).
        super("This change is above the approval limit and needs a second pair of eyes", null,
                false, false);
        this.change = change;
    }

    /** The unsaved row, for whoever parks it. */
    public PendingChange change() {
        return change;
    }
}
