package com.geneinvoice.region;

/**
 * Told about a new region inside the transaction that creates it, so whatever a region must start with
 * (the default region's approval thresholds, SC-4) is there before anyone can act in it.
 */
public interface RegionCreatedListener {

    void regionCreated(Region region);
}
