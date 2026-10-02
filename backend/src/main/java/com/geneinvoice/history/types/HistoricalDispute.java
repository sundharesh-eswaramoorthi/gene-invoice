package com.geneinvoice.history.types;

import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.history.Historical;

/** A dispute as it was at a point. */
public class HistoricalDispute extends Dispute implements Historical {

    /**
     * Whether a version was of an internal dispute. A version written before disputes could be internal has no value
     * (the upgrade fills it in, but a version is never trusted to have been): it was a customer's own (§3.11, CF-11).
     */
    static boolean internal(Boolean stored) {
        return Boolean.TRUE.equals(stored);
    }
}
