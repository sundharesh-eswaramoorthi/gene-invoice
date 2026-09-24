package com.geneinvoice.region;

/**
 * How a row answers the question "which region is this in?". Region is anchored on customers and
 * nowhere else, so every axis but NONE is ultimately a statement about one customer's placement —
 * which is why a customer moving branch moves its invoices, payments, promises, disputes, POC
 * seats, documents and emails in the same breath, with no child row to rewrite (B1).
 */
public enum RegionAxis {

    /** Deliberately unregioned. RegionAxes.UNREGIONED_BECAUSE must carry a sentence saying why. */
    NONE,

    /** The Customer itself: {@code root.region.id}. */
    OWN,

    /**
     * A flat {@code region_id} Long on the row itself, with no association to walk. Not one of
     * B1's four: maker-checker's PendingChange records the region a held change was made in, and
     * it has no customer to ask (B1, B2 INTEGRATION).
     */
    OWN_ID,

    /** Invoice/Payment/PaymentPromise: {@code root.customer.region.id} over a LEFT join. */
    VIA_CUSTOMER,

    /** Dispute and friends: a raw {@code customer_id} Long, resolved by an EXISTS over customers. */
    VIA_CUSTOMER_ID,

    /** User: a person is visible when they work somewhere the caller can see. */
    VIA_USER_GRANTS
}
