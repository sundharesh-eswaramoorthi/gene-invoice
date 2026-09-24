package com.geneinvoice.approval;

/**
 * The whole interceptable surface, once. Fourteen constants is not a starting point: every money
 * write in the application is one of them, and the complement is listed with a {@code // not gated}
 * comment at its own call site so the absence is a decision somebody wrote down (B2).
 *
 * <p>The applier switches over this enum as a switch expression with no {@code default}, so a
 * fifteenth constant added without an applier branch is a compile error rather than a change that
 * can be raised and never replayed (B2).
 */
public enum PendingAction {

    //                        target type                      create?  always-checked?
    PAYMENT_RECORD            (PendingTargetType.PAYMENT,  true,  false),
    PAYMENT_UPDATE_AMOUNT     (PendingTargetType.PAYMENT,  false, false),
    PAYMENT_VOID              (PendingTargetType.PAYMENT,  false, false),
    INVOICE_CREATE            (PendingTargetType.INVOICE,  true,  false),
    INVOICE_CANCEL            (PendingTargetType.INVOICE,  false, false),
    INVOICE_CANCEL_WITH_REFUND(PendingTargetType.INVOICE,  false, false),
    INVOICE_REPLACE_ITEMS     (PendingTargetType.INVOICE,  false, false),
    PROMISE_CREATE            (PendingTargetType.PROMISE,  true,  false),
    PROMISE_UPDATE            (PendingTargetType.PROMISE,  false, false),
    PROMISE_CANCEL            (PendingTargetType.PROMISE,  false, false),
    PROMISE_OVERRIDE          (PendingTargetType.PROMISE,  false, false),
    CUSTOMER_DELETE           (PendingTargetType.CUSTOMER, false, true),
    DISPUTE_APPROVE           (PendingTargetType.DISPUTE,  false, false),
    APPROVAL_THRESHOLD_SET    (PendingTargetType.REGION,   false, true);

    private final PendingTargetType targetType;
    private final boolean creating;
    private final boolean always;

    PendingAction(PendingTargetType targetType, boolean creating, boolean always) {
        this.targetType = targetType;
        this.creating = creating;
        this.always = always;
    }

    public PendingTargetType targetType() {
        return targetType;
    }

    /** True when there is no record yet: target_id is null and customer_id is the anchor (B2). */
    public boolean creating() {
        return creating;
    }

    /** Held whatever the amount: CUSTOMER_DELETE's cascade cannot be undone (CP-04), and a
     *  threshold change is how you would otherwise raise your own gate, push the money through
     *  and lower it again (B2). */
    public boolean alwaysChecked() {
        return always;
    }
}
