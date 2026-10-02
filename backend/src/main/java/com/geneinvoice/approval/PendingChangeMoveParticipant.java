package com.geneinvoice.approval;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionMoveParticipant;
import com.geneinvoice.region.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Changes waiting on a customer that is moved to another region (D7, SC-4). A move must never let someone without
 * the approval right silently discard other people's requests, nor be a way round a limit, so it is refused while
 * any are waiting, unless the mover approves changes in the region the customer leaves; then they are closed, and
 * their makers told. A change keeps the region it was asked in, so its history says where it waited.
 */
@Component
@RequiredArgsConstructor
public class PendingChangeMoveParticipant implements RegionMoveParticipant {

    private final PendingChangeRepository pending;
    private final PendingChangeCloser closer;
    private final CustomerRepository customerRepository;
    private final RegionRepository regionRepository;
    private final CurrentUser currentUser;

    /** The customer is locked by the move; its waiting changes are locked here, after it (§5.11.5). */
    @Override
    public void beforeCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        int waiting = pending.findForCustomerForUpdate(customerId, PendingStatus.PENDING).size();
        if (waiting == 0 || currentUser.hasIn(Privileges.CHANGE_APPROVE, fromRegionId)) return;
        String name = customerRepository.findById(customerId).map(c -> c.getName()).orElse("The customer");
        throw new BadRequestException(name + " has " + waiting + (waiting == 1 ? " change" : " changes")
                + " awaiting approval; they must be decided first");
    }

    @Override
    public int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        return closer.closeForCustomer(customerId, "Customer moved from " + nameOf(fromRegionId) + " to "
                + nameOf(toRegionId));
    }

    private String nameOf(Long regionId) {
        return regionRepository.findById(regionId).map(Region::getName).orElse("region #" + regionId);
    }
}
