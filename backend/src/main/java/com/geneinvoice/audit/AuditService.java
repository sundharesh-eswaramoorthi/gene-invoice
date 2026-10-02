package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;

    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Object before, Object after,
                           Long userId, Long disputeId, String reason) {
        return save(entityType, entityId, action, before, after, userId, disputeId, null, reason, null);
    }

    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Object before, Object after,
                           Long userId, Long disputeId, String reason, Long regionId) {
        return save(entityType, entityId, action, before, after, userId, disputeId, null, reason, regionId);
    }

    /** A row about a pending change: it names the change even when no approval is under way. */
    @Transactional
    public AuditLog recordLinked(String entityType, Long entityId, String action,
                                 Object before, Object after,
                                 Long userId, Long disputeId, Long pendingChangeId, String reason) {
        return save(entityType, entityId, action, before, after, userId, disputeId, pendingChangeId, reason, null);
    }

    static final int REASON_MAX = 500;

    private AuditLog save(String entityType, Long entityId, String action, Object before, Object after,
                          Long userId, Long disputeId, Long pendingChangeId, String reason, Long regionId) {
        WriteOrigin origin = WriteOrigins.current(currentUser);
        return repository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .changedByUserId(userId)
                .disputeId(disputeId)
                .reason(Fit.to(reason, REASON_MAX))
                .regionId(regionId)
                .origin(origin.kind() == WriteOrigin.Kind.USER ? null : origin.kind().name())
                .ruleId(origin.ruleId())
                .pendingChangeId(pendingChangeId != null ? pendingChangeId : origin.pendingChangeId())
                .approvedByUserId(origin.checkerUserId())
                .build());
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
