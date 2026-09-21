package com.geneinvoice.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * How many are still live on a record, for the tab's badge. Counted by what is <em>not</em>
     * terminal rather than by listing OPEN and IN_PROGRESS, so a status added later is counted as
     * open until somebody says otherwise, instead of silently vanishing from the badge (T5).
     */
    long countByEntityTypeAndEntityIdAndStatusNotIn(TaskEntityType entityType, Long entityId,
                                                    Collection<TaskStatus> statuses);

    /** A record's tasks in the order they were raised, for the panel on the record itself. */
    List<Task> findByEntityTypeAndEntityIdOrderByIdAsc(TaskEntityType entityType, Long entityId);

    List<Task> findByCustomerId(Long customerId);

    /**
     * Drops every task of a customer being deleted — its own, and those on its invoices and
     * payments, which is what {@code customer_id} is on the row for (T6). A task points at its
     * record by id with no foreign key to stop it, so without this the rows would outlive the
     * customer and the list would show work on records that no longer exist.
     *
     * <p>Written as a bulk delete rather than a load-and-remove because a busy customer can have
     * thousands, and it runs in the customer delete's own transaction so the two go together or
     * not at all. It clears the persistence context, which would otherwise keep serving the rows
     * just deleted. The matching {@code assignees} rows are swept by
     * {@code AssigneeRepository.deleteForCustomer} for every kind of owner at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Task t where t.customerId = :customerId")
    int deleteByCustomerId(@Param("customerId") Long customerId);
}
