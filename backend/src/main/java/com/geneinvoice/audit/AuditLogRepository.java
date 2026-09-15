package com.geneinvoice.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);
    List<AuditLog> findByEntityTypeAndEntityIdIn(String entityType, Collection<Long> entityIds);
    List<AuditLog> findByDisputeIdOrderByCreatedAtDesc(Long disputeId);
}
