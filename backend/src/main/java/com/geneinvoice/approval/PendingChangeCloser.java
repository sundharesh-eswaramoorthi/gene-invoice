package com.geneinvoice.approval;

import com.geneinvoice.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Closes the changes still waiting on a record that has just gone, been cancelled or been resolved (§5.11.7):
 * nobody decides them, their makers are told why, and each record's key is free again. Runs in the writer's
 * transaction, after its own locks (the pending rows come last, §5.11.5). The change an approval is applying
 * right now is left for the approval to finish.
 */
@Component
@RequiredArgsConstructor
public class PendingChangeCloser {

    public static final String CHANGE_CLOSED = "CHANGE_CLOSED";

    private final PendingChangeRepository pending;
    private final AuditService auditService;
    private final ApprovalNotifier notifier;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public int closeForCustomer(Long customerId, String reason) {
        return close(pending.findForCustomerForUpdate(customerId, PendingStatus.PENDING), reason);
    }

    /** The changes to the record itself, and those whose money it is (a dispute's approval on its target). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int closeForRecord(String entityType, Long entityId, String reason) {
        return close(pending.findOnRecordForUpdate(entityType, entityId, PendingStatus.PENDING), reason);
    }

    private int close(List<PendingChange> open, String reason) {
        Optional<Long> applying = ApprovalContext.current().map(ApprovalContext.Applying::pendingChangeId);
        Instant now = clock.instant();
        int closed = 0;
        for (PendingChange pc : open) {
            if (applying.isPresent() && applying.get().equals(pc.getId())) continue;
            pc.close(reason, now);
            auditService.recordLinked(ApprovalService.auditType(pc), ApprovalService.auditId(pc), CHANGE_CLOSED,
                    null, null, null, ApprovalService.disputeId(pc), pc.getId(), reason);
            notifier.decided(pc);
            closed++;
        }
        return closed;
    }
}
