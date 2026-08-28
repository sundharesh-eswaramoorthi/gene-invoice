package com.geneinvoice.creditnote;

import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/invoices/{invoiceId}/credit-notes")
@RequiredArgsConstructor
public class CreditNoteController {

    private final CreditNoteService service;
    private final CreditNoteIssueCoordinator coordinator;

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public List<CreditNoteDtos.CreditNoteDto> history(@PathVariable Long invoiceId) {
        return service.historyForInvoice(invoiceId).stream()
                .map(CreditNoteDtos.CreditNoteDto::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.IssueOutcome issue(@PathVariable Long invoiceId,
                                             @Valid @RequestBody CreditNoteDtos.IssueCreditNoteRequest req) {
        return coordinator.issue(invoiceId, req);
    }

    @PostMapping("/{noteId}/void")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public CreditNoteDtos.CreditNoteDto voidNote(@PathVariable Long invoiceId, @PathVariable Long noteId) {
        return CreditNoteDtos.CreditNoteDto.from(service.voidNote(invoiceId, noteId));
    }
}
