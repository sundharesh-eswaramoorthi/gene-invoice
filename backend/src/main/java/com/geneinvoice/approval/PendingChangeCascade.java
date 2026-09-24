package com.geneinvoice.approval;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * What happens to a change that is still waiting when the thing it is about goes away.
 *
 * <p>The documentCascade / emailCascade shape, called from CustomerService.delete on the line
 * BEFORE documentCascade so children go before parents. A waiting change on an account that no
 * longer exists can never be approved and can never be rejected by anybody who would recognise
 * it, so it is closed here with a reason rather than left in the queue for ever (B2, CP-04).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingChangeCascade {

    static final String DELETED = "The account was deleted";

    private static final String CHANGE_SUPERSEDED = "CHANGE_SUPERSEDED";
    private static final String NOTIF_TITLE = "Your change can no longer be approved";

    private final PendingChangeRepository repository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ApprovalContext context;

    @Transactional
    public int onCustomerDeleted(Long customerId) {
        int closed = supersede(
                repository.findByCustomerIdAndStatus(customerId, PendingChangeStatus.PENDING),
                DELETED);
        if (closed > 0) {
            log.info("Superseded {} waiting change(s) of deleted customer {}", closed, customerId);
        }
        return closed;
    }

    /**
     * Close these, say why, and tell whoever raised them (B2).
     *
     * <p>Package-private and shared with {@link PendingChangeRegionSync} because a move and a
     * deletion end a waiting change for the same reason — the record it was composed against is
     * not the record an approver would be deciding on any more — and two copies of this loop
     * would be two chances to forget the decided_at rider.
     *
     * <p>EVERY terminal transition sets decided_at, or B3's as-of read reports a superseded row as
     * outstanding for ever (B2, B3 INTEGRATION).
     */
    int supersede(List<PendingChange> waiting, String reason) {
        int closed = 0;
        for (PendingChange pc : waiting) {
            // The one change that must survive this is the change being applied: approving a
            // CUSTOMER_DELETE runs CustomerService.delete, which runs this, and a change that
            // superseded itself on the way to being approved would leave the account deleted and
            // the row saying nobody ever agreed to it (B2).
            if (pc.getId() != null && pc.getId().equals(context.applyingId())) continue;
            pc.setStatus(PendingChangeStatus.SUPERSEDED);
            pc.setDecidedAt(Instant.now());
            pc.setDecisionNotes(reason);
            // saveAndFlush and not save: the @PreUpdate that releases pending_key runs at flush,
            // and Hibernate's action queue runs every INSERT before every UPDATE. A caller that
            // closes a change and raises its replacement in one transaction would otherwise meet
            // uq_pending_open, exactly as RegionCustodyService.move meets uk_crh_open (B2, B1).
            PendingChange saved = repository.saveAndFlush(pc);
            auditService.record(ApprovalService.anchorType(saved), ApprovalService.anchorId(saved),
                    CHANGE_SUPERSEDED, null, ApprovalDtos.PendingChangeDto.of(saved),
                    // Nobody decided this: it was decided for them by something that happened to
                    // the record. A fabricated actor id would be a lie in the trail (B2).
                    null, null, saved.getId(), reason);
            if (saved.getRequestedByUserId() != null) {
                notificationService.notify(saved.getRequestedByUserId(), CHANGE_SUPERSEDED,
                        NOTIF_TITLE, reason + ": " + saved.getSummary(),
                        "/approvals/" + saved.getId());
            }
            closed++;
        }
        return closed;
    }
}
