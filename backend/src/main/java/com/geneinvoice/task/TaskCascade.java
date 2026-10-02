package com.geneinvoice.task;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * What a deleted customer does to its tasks, and to those on its invoices and payments: they stay, marked as on a
 * record that is gone, and any still to be done is cancelled. Through the entities, never bulk SQL, so their
 * history is captured (§5.9.2). Repositories only: the customer service calls it (CF-3).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCascade {

    private final TaskRepository repository;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public int onCustomerDeleted(Long customerId) {
        Instant now = clock.instant();
        Long actor = WriteOrigins.actor(currentUser);
        int cancelled = 0;
        for (Task task : repository.findByCustomerIdOrderByIdAsc(customerId)) {
            TaskDtos.TaskAuditSnapshot before = TaskService.snapshot(task);
            task.setEntityDeleted(true);
            boolean open = task.getStatus().isOpenWork();
            if (open) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setCancelledAt(now);
                cancelled++;
            }
            repository.save(task);
            auditService.record(TaskService.ENTITY, task.getId(), "TASK_CANCELLED_WITH_RECORD", before,
                    TaskService.snapshot(task), actor, null,
                    open ? "The customer was deleted" : "The customer was deleted; the task was already closed",
                    Region.idOf(task.getRegion()));
        }
        if (cancelled > 0) log.info("Cancelled {} open task(s) of deleted customer {}", cancelled, customerId);
        return cancelled;
    }
}
