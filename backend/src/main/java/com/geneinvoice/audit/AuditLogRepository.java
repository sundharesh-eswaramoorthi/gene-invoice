package com.geneinvoice.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);
    List<AuditLog> findByDisputeIdOrderByCreatedAtDesc(Long disputeId);
    @Query("SELECT a.entityId AS entityId, COUNT(a.id) AS reminderCount "
            + "FROM AuditLog a "
            + "WHERE a.entityType = 'INVOICE' "
            + "AND a.entityId IN :invoiceIds "
            + "AND a.action LIKE 'OVERDUE\\_REMINDER\\_STEP\\_%' ESCAPE '\\' "
            + "GROUP BY a.entityId")
    List<ReminderCountProjection> countOverdueRemindersByEntityIds(@Param("invoiceIds") Collection<Long> invoiceIds);

    interface ReminderCountProjection {
        Long getEntityId();
        long getReminderCount();
    }
}
