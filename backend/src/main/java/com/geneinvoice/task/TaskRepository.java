package com.geneinvoice.task;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /** The customer alone, read without a lock: what a change locks first, before the task (§5.11.5). */
    @Query("select t.customerId from Task t where t.id = :id")
    Optional<Long> customerIdOf(@Param("id") Long id);

    /** What a change checks before it takes any lock: the record the task is on, and the task's region. */
    interface Head {
        TaskEntityType getEntityType();

        Long getEntityId();

        Long getRegionId();

        boolean isEntityDeleted();
    }

    @Query("select t.entityType as entityType, t.entityId as entityId, t.region.id as regionId,"
            + " t.entityDeleted as entityDeleted from Task t where t.id = :id")
    Optional<Head> headOf(@Param("id") Long id);

    @Query("select t.id from Task t where t.customerId = :customerId order by t.id")
    List<Long> findIdsByCustomerId(@Param("customerId") Long customerId);

    List<Task> findByCustomerIdOrderByIdAsc(Long customerId);

    List<Task> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Task> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(TaskEntityType entityType, Long entityId);
}
