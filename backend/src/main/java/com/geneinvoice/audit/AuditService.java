package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
                .reason(fit(reason))
                .build());
    }

    static final int REASON_MAX = 500;

    private static String fit(String reason) {
        return reason == null || reason.length() <= REASON_MAX
                ? reason
                : reason.substring(0, REASON_MAX - 1) + "…";
    }

    @Transactional(readOnly = true)
    public List<AuditLog> historyFor(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
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
