package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Invoice-locked credit-note lifecycle. Issue and void acquire a PESSIMISTIC_WRITE lock on the
 * target invoice first, so competing credit actions validate against the serialized, then-current
 * invoice position. All validation happens before any mutation, refused actions write no audit
 * entry, and the REQUIRED audit write joins this transaction, so an audit failure rolls the
 * credit note and the invoice position back with it.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    public static final String AUDIT_ACTION_ISSUED = "CREDIT_NOTE_ISSUED";
    public static final String AUDIT_ACTION_VOIDED = "CREDIT_NOTE_VOIDED";

    private final CreditNoteRepository creditNoteRepository;
    private final InvoiceRepository invoiceRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    @Transactional
    public CreditNoteDtos.CreditNoteDto issue(Long invoiceId, CreditNoteDtos.IssueCreditNoteRequest req) {
        User issuer = currentUser.require();
        Invoice inv = lockOwnedInvoice(invoiceId);

        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot issue a credit note for a cancelled invoice");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException("A credit note reason is required");
        }
        BigDecimal amount = normalizeAmount(req.amount());

        BigDecimal creditedBefore = creditNoteRepository.sumActiveAmountByInvoiceId(inv.getId());
        BigDecimal creditable = InvoiceService.outstandingOf(inv, creditedBefore);
        if (amount.compareTo(creditable) > 0) {
            throw new BadRequestException("Amount exceeds the amount still creditable for this invoice: "
                    + creditable.toPlainString());
        }

        CreditNote note = creditNoteRepository.save(CreditNote.builder()
                .invoice(inv)
                .amount(amount)
                .reason(reason)
                .issuedByUserId(issuer.getId())
                .issuedByName(issuer.getFullName() != null ? issuer.getFullName() : issuer.getUsername())
                .issuedAt(Instant.now())
                .voided(false)
                .build());

        InvoiceService.recomputeStatus(inv, creditNoteRepository.sumActiveAmountByInvoiceId(inv.getId()));
        invoiceRepository.save(inv);

        auditService.record("INVOICE", inv.getId(), AUDIT_ACTION_ISSUED,
                null, CreditNoteDtos.CreditNoteDto.from(note),
                issuer.getId(), null, reason);

        return CreditNoteDtos.CreditNoteDto.from(note);
    }

    @Transactional
    public CreditNoteDtos.CreditNoteDto voidNote(Long invoiceId, Long noteId) {
        User caller = currentUser.require();
        Invoice inv = lockOwnedInvoice(invoiceId);

        // Reload the note only after the invoice lock is held, so the void decision is made
        // against state no competing credit action can be changing at the same time.
        CreditNote note = creditNoteRepository.findByIdAndInvoiceId(noteId, invoiceId)
                .orElseThrow(() -> new NotFoundException("Credit note not found"));
        if (note.isVoided()) {
            throw new BadRequestException("Credit note is already voided");
        }

        CreditNoteDtos.CreditNoteDto before = CreditNoteDtos.CreditNoteDto.from(note);
        note.markVoided();
        creditNoteRepository.save(note);

        // Voiding removes the note from the active credited sum; the paid state follows the
        // recalculated position in both directions, while CANCELLED stays authoritative.
        InvoiceService.recomputeStatus(inv, creditNoteRepository.sumActiveAmountByInvoiceId(inv.getId()));
        invoiceRepository.save(inv);

        auditService.record("INVOICE", inv.getId(), AUDIT_ACTION_VOIDED,
                before, CreditNoteDtos.CreditNoteDto.from(note),
                caller.getId(), null, note.getReason());

        return CreditNoteDtos.CreditNoteDto.from(note);
    }

    /** Full retained history for an invoice: active and voided notes, newest first. */
    @Transactional(readOnly = true)
    public List<CreditNoteDtos.CreditNoteDto> listForInvoice(Long invoiceId) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        enforceOwnership(inv);
        return CreditNoteDtos.CreditNoteDto.fromAll(
                creditNoteRepository.findByInvoiceIdOrderByIssuedAtDesc(invoiceId));
    }

    private Invoice lockOwnedInvoice(Long invoiceId) {
        Invoice inv = invoiceRepository.findForUpdateById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        enforceOwnership(inv);
        return inv;
    }

    private void enforceOwnership(Invoice inv) {
        Long callerCustomer = currentUser.require().getCustomerId();
        if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    /** Require a strictly positive amount in the invoice's scale-2 monetary precision. */
    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BadRequestException("Amount is required");
        }
        if (amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BadRequestException("Amount must use at most 2 decimal places");
        }
    }
}
