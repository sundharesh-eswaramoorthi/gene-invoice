package com.geneinvoice.approval;

import com.geneinvoice.region.CustomerMoved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account changed branches, so the changes somebody raised in the branch it has just left stop
 * being approvable there (B2, B1 INTEGRATION).
 *
 * <p>SUPERSEDE, DO NOT RE-STAMP. pending_changes.region_id is written once at raise time and
 * never mutated, because B3 reads that column as the frozen raise-time region and re-stamping it
 * would make "which approvals were outstanding, in which branch, as of then" wrong for ever. It
 * also closes the move-then-approve attack more decisively than re-stamping does: a maker who
 * moves an account into a friendlier branch does not walk their waiting change along with it,
 * they lose it and have to raise it again where the account now is (B2, B3 INTEGRATION).
 *
 * <p>Merely appearing on the classpath wires this: R7 injects {@link CustomerMoved} as a
 * collection, so the region package never learns that approvals exist. This class and
 * RegionLookupImpl are the only two files in com.geneinvoice.approval that name
 * com.geneinvoice.region at all, and this one names exactly the interface it implements (B2, B1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingChangeRegionSync implements CustomerMoved {

    private final PendingChangeRepository repository;
    private final PendingChangeCascade cascade;
    private final RegionLookup regions;

    /**
     * Fired inside the move's own transaction, after customers.region_id and the placement row
     * have both been written — so throwing here would take the move down with it, which is why
     * nothing in this body is allowed to fail on a change it merely cannot describe (B1, B2).
     */
    @Override
    @Transactional
    public void moved(Long customerId, Long fromRegionId, Long toRegionId) {
        // The branch NAME and not its code: RegionLookup is this package's only doorway to
        // regions and it answers in names, which is also what the person reading this sentence in
        // their notification would have called the place (B2, B1).
        String to = regions.regionName(toRegionId);
        int closed = cascade.supersede(
                repository.findByCustomerIdAndStatus(customerId, PendingChangeStatus.PENDING),
                "The account moved to " + (to == null ? "another branch" : to)
                        + "; raise the change again");
        if (closed > 0) {
            log.info("Superseded {} waiting change(s) of customer {} moved from region {} to {}",
                    closed, customerId, fromRegionId, toRegionId);
        }
    }
}
