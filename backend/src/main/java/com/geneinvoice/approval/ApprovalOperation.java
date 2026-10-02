package com.geneinvoice.approval;

import com.geneinvoice.privilege.Privileges;

/**
 * The money-moving changes a region can put behind a second pair of eyes (B2, §5.11.1). Stored by name through
 * a converter (no CHECK, no ENUM type), so a constant can be appended without widening anything; new constants
 * are appended last.
 */
public enum ApprovalOperation {
    INVOICE_CREATE("INVOICE", Privileges.INVOICE_VIEW, "Create invoice", "Invoice total", true),
    INVOICE_CANCEL("INVOICE", Privileges.INVOICE_VIEW, "Cancel invoice", "Invoice total", false),
    INVOICE_TERMS("INVOICE", Privileges.INVOICE_VIEW, "Extend invoice due date",
            "Balance whose due date moves later", false),
    PAYMENT_RECORD("PAYMENT", Privileges.PAYMENT_VIEW, "Record payment", "Payment amount", true),
    PROMISE_CREATE("PROMISE", Privileges.PROMISE_VIEW, "Create promise", "Promised amount", true),
    PROMISE_UPDATE("PROMISE", Privileges.PROMISE_VIEW, "Change promise", "Change in the promised amount", false),
    PROMISE_OVERRIDE("PROMISE", Privileges.PROMISE_VIEW, "Override promise status", "Unfulfilled amount", false),
    DISPUTE_APPROVE("DISPUTE", Privileges.DISPUTE_VIEW, "Approve dispute", "Amount the resolution moves", false),
    /**
     * Loosening a region's own limits (§5.11.11): held for another approver whatever it is worth, so it has no
     * amount and never a row of its own in {@code approval_thresholds}. Its record is the region.
     */
    THRESHOLD_CHANGE("REGION", Privileges.APPROVAL_THRESHOLD_MANAGE, "Loosen approval limits", "Limits loosened",
            false);

    private final String entityType;
    private final String viewPrivilege;
    private final String label;
    private final String measureLabel;
    private final boolean create;

    ApprovalOperation(String entityType, String viewPrivilege, String label, String measureLabel, boolean create) {
        this.entityType = entityType;
        this.viewPrivilege = viewPrivilege;
        this.label = label;
        this.measureLabel = measureLabel;
        this.create = create;
    }

    /** The kind of record the change is about: INVOICE, PAYMENT, PROMISE, DISPUTE, or REGION for its limits. */
    public String entityType() {
        return entityType;
    }

    /** Who may see the record may see the change on it (§5.11.9). */
    public String viewPrivilege() {
        return viewPrivilege;
    }

    public String label() {
        return label;
    }

    /** What the amount of a change of this kind measures, for the approver. */
    public String measureLabel() {
        return measureLabel;
    }

    /** A create has no record yet, so no outstanding key: several may wait at once. */
    public boolean isCreate() {
        return create;
    }

    /** Gated by an amount threshold row in {@code approval_thresholds}. */
    public boolean amountGated() {
        return this != THRESHOLD_CHANGE;
    }
}
