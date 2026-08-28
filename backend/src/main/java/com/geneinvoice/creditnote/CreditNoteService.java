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
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single authority for credit-note state transitions. Every mutation runs in one
 * transaction that holds the invoice row's pessimistic-write lock before any status,
 * payment or note figure is read, and its INVOICE audit entry is written inside that
 * same transaction, so a note and its audit record commit together or not at all.
 * Amounts above the committed credit-aware outstanding (total minus paidAmount minus
 * the ACTIVE-note sum) are refused outright - never clamped - and the refusal states
 * the then-current remaining creditable amount.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    /**
     * Issue one ACTIVE credit note against an invoice. Trims and re-validates the amount
     * and reason, takes the invoice's write lock before any check, refuses a CANCELLED
     * invoice and any amount above the committed credit-aware outstanding, then inserts
     * the note, recomputes status credit-aware and audits before/after snapshots - all
     * atomically. Audit failure rolls the note back.
     */
    @Transactional
    public CreditNote issue(Long invoiceId, CreditNoteDtos.IssueCreditNoteRequest req) {
        BigDecimal amount = req != null ? req.amount() : null;
        String reason = req != null && req.reason() != null ? req.reason().trim() : null;
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be positive");
        }
        if (reason == null || reason.isEmpty()) {
            throw new BadRequestException("Reason must not be blank");
        }

        Invoice inv = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireOwnership(inv);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot issue a credit note for a cancelled invoice");
        }

        BigDecimal creditedBefore = creditNoteRepository.sumActiveAmountForInvoice(invoiceId);
        BigDecimal remaining = InvoiceService.creditAwareOutstanding(inv, creditedBefore);
        if (amount.compareTo(remaining) > 0) {
            throw new BadRequestException(
                    "Credit amount exceeds the remaining creditable amount of " + remaining);
        }

        Long userId = currentUser.require().getId();
        Object before = InvoiceDtos.InvoiceDto.from(inv, creditedBefore);

        CreditNote note = CreditNote.builder()
                .invoice(inv)
                .amount(amount)
                .reason(reason)
                .issuedByUserId(userId)
                .status(CreditNoteStatus.ACTIVE)
                .build();
        note = creditNoteRepository.save(note);

        BigDecimal creditedAfter = creditedBefore.add(amount);
        InvoiceService.recomputeStatus(inv, creditedAfter);
        invoiceRepository.save(inv);

        Object after = InvoiceDtos.InvoiceDto.from(inv, creditedAfter);
        auditService.record("INVOICE", inv.getId(), "CREDIT_NOTE_ISSUED",
                before, after, userId, null, reason);
        return note;
    }

    /**
     * Void a retained credit note exactly once: locks the note and the invoice, refuses an
     * already-VOIDED note without touching any balance, transitions ACTIVE to VOIDED and
     * recomputes from the remaining ACTIVE sum. Voiding a note on a CANCELLED invoice is
     * permitted and the invoice stays CANCELLED.
     */
    @Transactional
    public CreditNote voidNote(Long invoiceId, Long noteId) {
        CreditNote note = creditNoteRepository.findByIdForUpdate(noteId)
                .orElseThrow(() -> new NotFoundException("Credit note not found"));
        Invoice inv = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!note.getInvoice().getId().equals(inv.getId())) {
            throw new NotFoundException("Credit note not found");
        }
        requireOwnership(inv);
        if (note.getStatus() == CreditNoteStatus.VOIDED) {
            throw new BadRequestException("Credit note already voided");
        }

        Long userId = currentUser.require().getId();
        BigDecimal creditedBefore = creditNoteRepository.sumActiveAmountForInvoice(invoiceId);
        Object before = InvoiceDtos.InvoiceDto.from(inv, creditedBefore);

        note.setStatus(CreditNoteStatus.VOIDED);
        creditNoteRepository.save(note);

        BigDecimal creditedAfter = creditedBefore.subtract(note.getAmount());
        InvoiceService.recomputeStatus(inv, creditedAfter);
        invoiceRepository.save(inv);

        Object after = InvoiceDtos.InvoiceDto.from(inv, creditedAfter);
        auditService.record("INVOICE", inv.getId(), "CREDIT_NOTE_VOIDED",
                before, after, userId, null, "Voided credit note #" + note.getId());
        return note;
    }

    /**
     * The invoice's complete credit-note history, ACTIVE and VOIDED together, after the
     * same customer-ownership check every operation here applies.
     */
    @Transactional(readOnly = true)
    public List<CreditNote> historyForInvoice(Long invoiceId) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireOwnership(inv);
        return creditNoteRepository.findByInvoiceIdOrderByIssuedAtAscIdAsc(invoiceId);
    }

    /** Mirror of InvoiceService.get: customer callers may reach only their own invoices. */
    private void requireOwnership(Invoice inv) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
    }
}
