package com.geneinvoice.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What happens to a customer's tasks when the customer goes, beside DocumentCascade and
 * EmailCascade (A6).
 *
 * <p>Tasks are DELETED outright, unlike emails, which are kept because they record something that
 * was said: a task is work somebody owes on a record, and when the record is gone there is no work
 * left to owe. Keeping them would leave rows whose customer_id — the region axis — points at
 * nothing, so they would be visible from no branch and reachable by nobody (A6, B1, CP-13).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCascade {

    private final TaskRepository taskRepository;
    private final TaskAssigneeRepository assigneeRepository;

    public int onCustomerDeleted(Long customerId) {
        List<Task> tasks = taskRepository.findByCustomerId(customerId);
        if (tasks.isEmpty()) return 0;
        List<Long> ids = tasks.stream().map(Task::getId).toList();

        assigneeRepository.deleteAll(assigneeRepository.findByTaskIdInOrderByTaskIdAscIdAsc(ids));
        // task_assignees.task_id is the ONE foreign key in the programme, so the children have to
        // reach the database before their parents do. Hibernate executes deletes in the order they
        // were queued, but the flush that carries them is not ordered against the one below, and
        // Postgres enforces the key where H2 is more forgiving (A6).
        assigneeRepository.flush();

        taskRepository.deleteAll(tasks);
        log.info("Deleted {} task(s) of deleted customer {}", tasks.size(), customerId);
        return tasks.size();
    }
}
