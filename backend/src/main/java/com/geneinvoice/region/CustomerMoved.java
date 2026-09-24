package com.geneinvoice.region;

/**
 * Something that has to know an account changed branches.
 *
 * <p>Fired once by {@link RegionCustodyService#move}, inside the move's own transaction, after
 * customers.region_id and the customer_region_history row have both been written — so a listener
 * that reads either one reads the new placement, and a listener that throws takes the move down
 * with it rather than leaving the two halves disagreeing (B1).
 *
 * <p>It exists as an interface, injected as a collection that is EMPTY today, because the region
 * package must never import approval, task, automation or history. Anything that has to react to a
 * move implements this from its own package and Spring hands it over; the region package never
 * learns that the listener exists (B1).
 *
 * <p>The first implementation is B2's PendingChangeRegionSync: a change raised while the account
 * was in the region it has just left is SUPERSEDED rather than re-stamped, which keeps a pending
 * change immutable between raise and decision and closes the move-then-approve attack
 * (B1, B2 INTEGRATION).
 */
public interface CustomerMoved {

    /**
     * @param customerId   the account that moved
     * @param fromRegionId where it was, never null — every customer is placed
     * @param toRegionId   where it is now, never null and never equal to fromRegionId: a move to
     *                     the region an account is already in is not a move and fires nothing
     */
    void moved(Long customerId, Long fromRegionId, Long toRegionId);
}
