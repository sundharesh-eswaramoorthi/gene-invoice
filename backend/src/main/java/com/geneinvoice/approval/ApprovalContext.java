package com.geneinvoice.approval;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The two things a replay carries that an ordinary request does not: that this call is already
 * approved, and who it is being made on behalf of (B2).
 *
 * <p>ThreadLocals rather than arguments because the value has to survive a journey through code
 * that knows nothing about approvals — a service, a private engine, another service — and
 * threading a flag through twelve signatures would put the decision in every one of them instead
 * of in the two places that set it (B2).
 */
@Component
public class ApprovalContext {

    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();

    // WHICH change is being replayed, not merely that one is. A replay can re-enter code that
    // supersedes every waiting change on an account — approving a CUSTOMER_DELETE runs
    // CustomerService.delete, which runs PendingChangeCascade — and the one change that must not
    // be superseded by its own application is the change being applied (B2).
    private static final ThreadLocal<Long> APPLYING_ID = new ThreadLocal<>();

    // A rule action has no principal, and a change with no maker would make "approval from
    // someone else" trivially satisfiable (B2, A5 INTEGRATION).
    private static final ThreadLocal<Long> ACTING_AS = new ThreadLocal<>();

    /**
     * Inside this, ApprovalGate.check is a no-op: the second pair of eyes is already here (B2).
     *
     * <p>{@code pendingChangeId} names the change being replayed, which is what lets anything the
     * replay re-enters leave that one change alone: PendingChangeCascade supersedes every waiting
     * change on a deleted account, and approving a CUSTOMER_DELETE deletes the account through
     * exactly that path, so without this the change would supersede itself on the way to being
     * approved (B2).
     */
    public <T> T applying(Long pendingChangeId, Supplier<T> work) {
        Boolean previous = APPLYING.get();
        Long previousId = APPLYING_ID.get();
        APPLYING.set(Boolean.TRUE);
        APPLYING_ID.set(pendingChangeId);
        try {
            return work.get();
        } finally {
            // Restored, never blindly cleared: an applier that re-enters another gated mutator
            // would otherwise hand the inner call's exit the job of switching the gate back on
            // halfway through the outer one (B2).
            if (previous == null) APPLYING.remove(); else APPLYING.set(previous);
            if (previousId == null) APPLYING_ID.remove(); else APPLYING_ID.set(previousId);
        }
    }

    public boolean isApplying() {
        return Boolean.TRUE.equals(APPLYING.get());
    }

    /** The change this thread is replaying, or null when it is replaying nothing (B2). */
    public Long applyingId() {
        return APPLYING_ID.get();
    }

    /**
     * The person this thread is acting for while there is nobody in the SecurityContext. Set by the
     * automation engine around a rule action, so the pending change a rule raises still names the
     * rule's author rather than nobody at all (B2, A5 INTEGRATION).
     */
    public <T> T actingAs(Long userId, Supplier<T> work) {
        Long previous = ACTING_AS.get();
        ACTING_AS.set(userId);
        try {
            return work.get();
        } finally {
            if (previous == null) ACTING_AS.remove(); else ACTING_AS.set(previous);
        }
    }

    public Long actingAs() {
        return ACTING_AS.get();
    }
}
