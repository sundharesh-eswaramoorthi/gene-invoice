package com.geneinvoice.approval;

import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who hears about a pending change (§5.11.11): the region's approvers when it is asked for (never the maker),
 * and the maker when it is decided for them. Nothing is sent when the maker withdraws or replaces it.
 */
@Component
@RequiredArgsConstructor
public class ApprovalNotifier {

    public static final String REQUESTED = "APPROVAL_REQUESTED";
    public static final String APPROVED = "APPROVAL_APPROVED";
    public static final String REJECTED = "APPROVAL_REJECTED";
    public static final String FAILED = "APPROVAL_FAILED";
    public static final String CLOSED = "APPROVAL_CLOSED";

    private final NotificationService notificationService;

    public void requested(PendingChange pc) {
        notificationService.notifyHolders(Privileges.CHANGE_APPROVE, pc.getRegionId(), pc.getMakerUserId(), REQUESTED,
                "Approval needed: " + pc.getOperation().label(), pc.getSummary(), link(pc));
    }

    public void decided(PendingChange pc) {
        String label = pc.getOperation().label();
        switch (pc.getStatus()) {
            case APPROVED -> tell(pc, APPROVED, label + " approved", pc.getSummary(), recordLink(pc));
            case REJECTED -> tell(pc, REJECTED, label + " rejected",
                    pc.getSummary() + (pc.getDecisionNote() == null ? "" : ": " + pc.getDecisionNote()), link(pc));
            case FAILED -> tell(pc, FAILED, label + " could not be applied",
                    pc.getSummary() + ": " + pc.getFailureReason(), link(pc));
            case CLOSED -> tell(pc, CLOSED, label + " closed",
                    pc.getSummary() + (pc.getDecisionNote() == null ? "" : ": " + pc.getDecisionNote()), link(pc));
            default -> { }
        }
    }

    private void tell(PendingChange pc, String type, String title, String message, String link) {
        notificationService.notify(pc.getMakerUserId(), type, title, message, link, pc.getRegionId());
    }

    static String link(PendingChange pc) {
        return "/approvals/" + pc.getId();
    }

    /** The record the approved change made or changed. */
    static String recordLink(PendingChange pc) {
        Long id = pc.getResultEntityId() != null ? pc.getResultEntityId() : pc.getEntityId();
        if (id == null) return link(pc);
        return switch (pc.getEntityType()) {
            case "CUSTOMER" -> "/customers/" + id;
            case "INVOICE" -> "/invoices/" + id;
            case "PAYMENT" -> "/payments/" + id;
            case "PROMISE" -> "/promises/" + id;
            case "DISPUTE" -> "/disputes/" + id;
            // A threshold change is on its region: open that region's thresholds, not this approval again.
            case "REGION" -> "/approval-thresholds?regionId=" + id;
            default -> link(pc);
        };
    }
}
