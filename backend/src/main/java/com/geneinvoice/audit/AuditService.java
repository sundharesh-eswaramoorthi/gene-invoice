package com.geneinvoice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.common.RecordChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Resolved per call, not injected: it lets the automation engine depend on audit without audit
     * depending on automation's bean graph, which Spring Boot 3 would otherwise reject as a
     * circular reference. An ObjectProvider with no candidates iterates nothing, so this class
     * behaves exactly as it did before the engine existed (A1).
     */
    private final ObjectProvider<RecordChanged> changed;                                  // (A1)

    /**
     * The eight-arg call every existing writer makes, kept byte-identical so the ~40 hand-placed
     * sites in this codebase compile untouched. A write nobody had to approve records a null
     * pending_change_id, which is what "nobody had to approve it" looks like in SQL (B2).
     */
    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Object before, Object after,
                           Long userId, Long disputeId, String reason) {
        return record(entityType, entityId, action, before, after, userId, disputeId, null, reason);
    }

    /**
     * The ONE implementation, and deliberately so rather than two builders side by side.
     *
     * <p>Every audit row in the product is written here, on both paths, which is what makes this
     * the place a later feature can hook a record-changed trigger onto without having to know
     * which of the two signatures its caller happened to use. A hook added to the eight-arg
     * delegate instead would silently fire nothing for everything B2 writes (B2, A1 INTEGRATION).
     */
    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Object before, Object after,
                           Long userId, Long disputeId, Long pendingChangeId, String reason) {
        AuditLog saved = repository.save(AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .changedByUserId(userId)
                .disputeId(disputeId)
                .pendingChangeId(pendingChangeId)
                .reason(fit(reason))
                .build());
        // Here, in the NINE-arg body, and not in the eight-arg delegate: both paths come through
        // this method, so a hook placed on the delegate would fire for every ordinary write and
        // silently for nothing an approval replays (A1, B2 INTEGRATION).
        changed.forEach(c -> c.changed(entityType, entityId, action));                    // (A1)
        return saved;
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
