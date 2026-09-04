package com.geneinvoice.creditnote;

import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/invoices/{invoiceId}/credit-notes")
@RequiredArgsConstructor
public class CreditNoteController {

    static final String NOTIFICATION_TYPE = "CREDIT_NOTE_ISSUED";
    static final String NOTIFICATION_WARNING_MESSAGE =
            "Credit note issued, but admin notifications could not be delivered";

    private final CreditNoteService creditNoteService;
    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public List<CreditNoteDtos.CreditNoteDto> list(@PathVariable Long invoiceId) {
        return creditNoteService.listForInvoice(invoiceId);
    }

    /**
     * Non-transactional response boundary: the transactional issue commits first, then the
     * all-admin notification fanout runs in its own transaction. A fanout failure never rolls
     * back the committed credit note or its audit entry; it is returned as success-with-warning
     * exactly once, with no retry or queueing.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.IssueCreditNoteResponse issue(@PathVariable Long invoiceId,
                                                        @Valid @RequestBody CreditNoteDtos.IssueCreditNoteRequest req) {
        CreditNoteDtos.CreditNoteDto note = creditNoteService.issue(invoiceId, req);
        try {
            notificationService.notifyAdmins(
                    NOTIFICATION_TYPE,
                    "Credit note issued on " + note.invoiceNumber(),
                    "Credit " + note.amount().toPlainString() + " on invoice "
                            + note.invoiceNumber() + ": " + note.reason(),
                    "/invoices");
            return new CreditNoteDtos.IssueCreditNoteResponse(note, false, null);
        } catch (RuntimeException ex) {
            log.warn("Admin notification failed after credit note {} was issued: {}", note.id(), ex.getMessage());
            return new CreditNoteDtos.IssueCreditNoteResponse(note, true, NOTIFICATION_WARNING_MESSAGE);
        }
    }

    @PostMapping("/{noteId}/void")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.CreditNoteDto voidNote(@PathVariable Long invoiceId, @PathVariable Long noteId) {
        return creditNoteService.voidNote(invoiceId, noteId);
    }
}
