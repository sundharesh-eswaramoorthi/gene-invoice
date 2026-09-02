package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Transactional invoice credit-note commands. Each method is demarcated by its
 * Spring proxy: the credit-note mutation, the invoice settlement recomputation
 * and the invoice audit entry commit or roll back together. On normal return the
 * command's work is COMMITTED — admin notification deliberately does not happen
 * here (see CreditNoteIssuanceCoordinator), so a notification failure can never
 * roll the issuance back.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteCommandService {

    public static final String AUDIT_ACTION_ISSUED = "CREDIT_NOTE_ISSUED";
    public static final String AUDIT_ACTION_VOIDED = "CREDIT_NOTE_VOIDED";
    private static final String AUDIT_ENTITY_TYPE = "INVOICE";

    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    @Transactional
    public CreditNoteDtos.CreditNoteResponse issue(Long invoiceId, CreditNoteDtos.IssueCreditNoteRequest req) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot issue a credit note for a cancelled invoice");
        }

        BigDecimal amount = req.amount();
        if (amount.signum() <= 0) {
            throw new BadRequestException("Credit note amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Credit note amount must have no more than 2 decimal places");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException("A reason is required");
        }

        User issuer = currentUser.require();

        // Commit-time recheck against the locked invoice's remaining creditable
        // amount (total - successful payments - active credit notes, floored at 0).
        // An excessive amount is refused wholly; it is never clamped or partially applied.
        BigDecimal creditable = invoice.getBalance();
        if (amount.compareTo(creditable) > 0) {
            throw new BadRequestException("Credit note amount exceeds the remaining creditable amount of "
                    + creditable.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }

        Object before = InvoiceDtos.InvoiceSummary.from(invoice);

        CreditNote note = CreditNote.issue(invoice, amount.setScale(2, RoundingMode.UNNECESSARY), reason, issuer);
        invoice.getCreditNotes().add(note);
        note = creditNoteRepository.save(note);
        InvoiceService.recomputeStatus(invoice);
        invoiceRepository.save(invoice);

        auditService.record(AUDIT_ENTITY_TYPE, invoice.getId(), AUDIT_ACTION_ISSUED,
                before, InvoiceDtos.InvoiceSummary.from(invoice), issuer.getId(), null, reason);

        return CreditNoteDtos.CreditNoteResponse.from(note, null);
    }

    @Transactional
    public CreditNoteDtos.CreditNoteResponse voidNote(Long invoiceId, Long creditNoteId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        CreditNote note = creditNoteRepository.findById(creditNoteId)
                .orElseThrow(() -> new NotFoundException("Credit note not found"));
        if (!note.getInvoice().getId().equals(invoice.getId())) {
            throw new NotFoundException("Credit note not found");
        }
        if (note.getStatus() != CreditNoteStatus.ACTIVE) {
            throw new BadRequestException("Credit note has already been voided");
        }

        User actor = currentUser.require();
        Object before = InvoiceDtos.InvoiceSummary.from(invoice);

        note.markVoided();
        creditNoteRepository.save(note);
        // recomputeStatus keeps a CANCELLED invoice CANCELLED while restoring
        // the note's amount to the invoice's outstanding.
        InvoiceService.recomputeStatus(invoice);
        invoiceRepository.save(invoice);

        auditService.record(AUDIT_ENTITY_TYPE, invoice.getId(), AUDIT_ACTION_VOIDED,
                before, InvoiceDtos.InvoiceSummary.from(invoice), actor.getId(), null, note.getReason());

        return CreditNoteDtos.CreditNoteResponse.from(note, null);
    }
}
