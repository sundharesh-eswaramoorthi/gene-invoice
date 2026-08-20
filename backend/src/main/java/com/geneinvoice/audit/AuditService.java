package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Object before, Object after,
                           Long userId, Long disputeId, String reason) {
        return repository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .changedByUserId(userId)
                .disputeId(disputeId)
                .reason(reason)
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditLog> historyFor(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }
    @Transactional(readOnly = true)
    public Map<Long, Long> reminderCountsByInvoiceId(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return repository.countOverdueRemindersByEntityIds(invoiceIds).stream()
                .collect(Collectors.toMap(
                        AuditLogRepository.ReminderCountProjection::getEntityId,
                        AuditLogRepository.ReminderCountProjection::getReminderCount));
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"_serialization_error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
