package com.geneinvoice.task;

import com.geneinvoice.region.CustomerMoved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * An account changed branches, so the people holding open work on it who do not work there give
 * the work up (A6, B1 INTEGRATION).
 *
 * <p>The second implementation of {@link CustomerMoved}, beside B2's PendingChangeRegionSync, and
 * it exists for the same reason: the region package must never import task, so anything that has
 * to react to a move implements the interface from its own package and Spring hands it over.
 *
 * <p>IT IS THE MIRROR OF THE ASSIGNMENT GATE. TaskService.requireAssignable refuses a seat for
 * somebody who cannot MANAGE the account's branch; without this, a move would create exactly the
 * state that gate refuses — a seat on a task its holder's region axis hides, so the assignee has
 * been told about work that answers 404 to her and can never be completed or handed on. The POC
 * book has had both halves since R8; task seats had neither.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskRegionSync implements CustomerMoved {

    private final TaskService taskService;

    /**
     * Fired inside the move's own transaction, after customers.region_id and the placement row
     * have both been written, so a throw here takes the move down with it rather than leaving the
     * seats and the branch disagreeing (B1).
     */
    @Override
    public void moved(Long customerId, Long fromRegionId, Long toRegionId) {
        int vacated = taskService.vacateSeatsWhoseHolderCannotManage(customerId, toRegionId);
        if (vacated > 0) {
            log.info("Vacated {} task seat(s) of customer {} moved from region {} to {}",
                    vacated, customerId, fromRegionId, toRegionId);
        }
    }
}
