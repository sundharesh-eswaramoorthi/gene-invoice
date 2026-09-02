package com.geneinvoice.creditnote;

import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit-note endpoints on an invoice. Issuance goes through the non-transactional
 * coordinator so the admin-notification consequence runs strictly after commit;
 * voiding has no notification consequence and invokes the transactional command
 * directly.
 */
@RestController
@RequestMapping("/api/invoices/{invoiceId}/credit-notes")
@RequiredArgsConstructor
public class CreditNoteController {

    private final CreditNoteIssuanceCoordinator issuanceCoordinator;
    private final CreditNoteCommandService commandService;

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.CreditNoteResponse issue(@PathVariable Long invoiceId,
                                                   @Valid @RequestBody CreditNoteDtos.IssueCreditNoteRequest req) {
        return issuanceCoordinator.issue(invoiceId, req);
    }

    @PostMapping("/{creditNoteId}/void")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.CreditNoteResponse voidNote(@PathVariable Long invoiceId,
                                                      @PathVariable Long creditNoteId) {
        return commandService.voidNote(invoiceId, creditNoteId);
    }
}
