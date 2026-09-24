package com.geneinvoice.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TaskAssigneeRepository extends JpaRepository<TaskAssignee, Long> {

    List<TaskAssignee> findByTaskIdOrderByIdAsc(Long taskId);

    /** One query for a whole page of tasks, rather than one per row (A6). */
    List<TaskAssignee> findByTaskIdInOrderByTaskIdAscIdAsc(Collection<Long> taskIds);
}
