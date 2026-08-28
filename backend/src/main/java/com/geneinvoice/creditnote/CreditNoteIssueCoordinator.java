package com.geneinvoice.creditnote;

import com.geneinvoice.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Post-commit issuance coordinator. Deliberately NOT transactional and a separate
 * bean from {@link CreditNoteService}: issue() runs through the service's proxy so
 * the credit note and its audit row fully commit before any notification is sent,
 * and a notification failure can never join or roll back the financial transaction.
 * No retry, scheduler or queue exists here - a failed notification is reported once
 * as a warning on an otherwise successful result.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteIssueCoordinator {

    private static final String NOTIF_CREDIT_NOTE_ISSUED = "CREDIT_NOTE_ISSUED";

    private final CreditNoteService creditNoteService;
    private final NotificationService notificationService;

    public CreditNoteDtos.IssueOutcome issue(Long invoiceId, CreditNoteDtos.IssueCreditNoteRequest req) {
        // Proxied @Transactional call: any issuance or audit failure propagates from
        // here unchanged and produces no result.
        CreditNote note = creditNoteService.issue(invoiceId, req);

        String warning = null;
        try {
            notificationService.notifyAdmins(NOTIF_CREDIT_NOTE_ISSUED,
                    "Credit note issued",
                    "Credit note " + note.getId() + " of " + note.getAmount()
                            + " was issued for invoice " + invoiceId + ": " + note.getReason(),
                    "/invoices/" + invoiceId);
        } catch (Exception e) {
            // The note is already committed; the notification failure is a warning, not an error.
            warning = "Credit note issued but admin notification failed: " + e.getMessage();
        }

        return new CreditNoteDtos.IssueOutcome(CreditNoteDtos.CreditNoteDto.from(note), warning);
    }
}
