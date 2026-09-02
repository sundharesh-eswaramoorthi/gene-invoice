package com.geneinvoice.creditnote;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Non-transactional sequencer for credit-note issuance.
 *
 * This bean intentionally carries NO @Transactional annotation (class or method):
 * the issue command is invoked through its Spring proxy, so the accounting change
 * (credit note, settlement recomputation, audit entry) commits when the proxied
 * command returns normally. Only after that committed return does the coordinator
 * start the per-admin notification consequence. A notification failure therefore
 * cannot roll back the committed note, balance effect or audit entry; it is
 * reported to the caller as a successful response carrying a warning instead, and
 * is never retried or queued. Any failure thrown by the command itself (a
 * pre-commit validation or persistence failure) propagates normally and no
 * notification is attempted.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteIssuanceCoordinator {

    public static final String NOTIFICATION_WARNING =
            "Credit note issued, but at least one admin notification could not be delivered";

    private final CreditNoteCommandService commandService;
    private final CreditNoteAdminNotifier adminNotifier;

    public CreditNoteDtos.CreditNoteResponse issue(Long invoiceId, CreditNoteDtos.IssueCreditNoteRequest req) {
        CreditNoteDtos.CreditNoteResponse issued = commandService.issue(invoiceId, req);
        boolean notificationFailed = adminNotifier.notifyAdminsOfIssuance(issued);
        return notificationFailed ? issued.withWarning(NOTIFICATION_WARNING) : issued;
    }
}
