package com.geneinvoice.dispute;

/** What a dispute is about. New constants go last: the column's CHECK or ENUM is widened by the first one (D12). */
public enum DisputeTargetType {
    INVOICE,
    PAYMENT,
    /** The customer as a whole: only automation raises one, and it proposes no change (§5.10.11). */
    CUSTOMER
}
