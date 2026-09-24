package com.geneinvoice.approval;

/**
 * The kind of record a held change is about. REGION is not a business record: it is how a change
 * to a region's own approval limit names what it is about, so one threshold change per region can
 * wait at a time under the same pending_key rule every other target uses (B2).
 */
public enum PendingTargetType {
    CUSTOMER,
    INVOICE,
    PAYMENT,
    PROMISE,
    DISPUTE,
    REGION
}
