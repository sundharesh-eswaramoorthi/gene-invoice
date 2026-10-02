package com.geneinvoice.task;

import com.geneinvoice.history.HistoryTracker;
import com.geneinvoice.region.RegionMoveParticipant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A customer's tasks move with it (§5.6.8 step 14): one native update of their region, as the move does for the
 * customer's other records, and the rows named to {@link HistoryTracker#touch} in the same transaction so each gets
 * a version at the move's instant in the new region.
 */
@Component
public class TaskMoveParticipant implements RegionMoveParticipant {

    @PersistenceContext
    private EntityManager em;

    private final TaskRepository taskRepository;
    private final HistoryTracker historyTracker;

    public TaskMoveParticipant(TaskRepository taskRepository, HistoryTracker historyTracker) {
        this.taskRepository = taskRepository;
        this.historyTracker = historyTracker;
    }

    @Override
    public int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {
        List<Long> ids = taskRepository.findIdsByCustomerId(customerId);
        if (ids.isEmpty()) return 0;
        em.flush();
        int moved = em.createNativeQuery("update tasks set region_id = :to where customer_id = :cid")
                .setParameter("to", toRegionId)
                .setParameter("cid", customerId)
                .executeUpdate();
        historyTracker.touch(Task.class, ids, false);
        return moved;
    }
}
