package com.geneinvoice.region;

/**
 * Told about an empty region inside the transaction that deletes it, before it goes, so whatever belongs to the
 * region alone (its approval thresholds) goes with it, or the delete is refused while something about it is still
 * waiting.
 */
public interface RegionDeletingListener {

    void regionDeleting(Region region);
}
