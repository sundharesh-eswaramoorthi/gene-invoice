package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The transactional issue/void commands: validation, the commit-time creditable
 * recheck under the invoice write lock, settlement recomputation and the atomic
 * invoice audit entry.
 */
class CreditNoteCommandServiceTest {

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final CreditNoteRepository creditNoteRepository = mock(CreditNoteRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final CreditNoteCommandService service =
            new CreditNoteCommandService(invoiceRepository, creditNoteRepository, auditService, currentUser);

    private static final User ISSUER = User.builder().id(2L).username("manager1").build();

    private static Invoice invoice(String total, String paid, InvoiceStatus status) {
        return invoice(42L, total, paid, status);
    }

    private static Invoice invoice(Long id, String total, String paid, InvoiceStatus status) {
        return Invoice.builder()
                .id(id)
                .invoiceNumber("INV-0042")
                .customer(Customer.builder().id(7L).name("Acme Corp").build())
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build();
    }

    private Invoice givenLockedInvoice(Invoice inv) {
        when(invoiceRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(inv));
        when(currentUser.require()).thenReturn(ISSUER);
        when(creditNoteRepository.save(any())).thenAnswer(a -> a.getArgument(0));
        return inv;
    }

    @Test
    void issueTrimsReasonCapturesIssuerAndServerTimeAndRecordsInvoiceAudit() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));

        CreditNoteDtos.CreditNoteResponse res = service.issue(42L,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("30"), "  Overcharged  "));

        // Immutable evidence: trimmed reason, scale-2 amount, authenticated issuer, server time.
        assertEquals(0, res.amount().compareTo(new BigDecimal("30.00")));
        assertEquals("Overcharged", res.reason());
        assertEquals("manager1", res.issuedBy());
        assertEquals(Instant.class, res.issuedAt().getClass());
        assertEquals(CreditNoteStatus.ACTIVE, res.status());
        assertEquals("INV-0042", res.invoiceNumber());
        assertNull(res.warning());
        // 30 credited of 100 outstanding -> partially paid via credits alone.
        assertEquals(InvoiceStatus.PARTIALLY_PAID, inv.getStatus());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("70.00")));
        // paidAmount (cash) untouched by the credit.
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("0.00")));

        // Atomic audit entry on entityType INVOICE with the owning invoice id.
        ArgumentCaptor<Object> beforeCap = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> afterCap = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq("INVOICE"), eq(42L), eq("CREDIT_NOTE_ISSUED"),
                beforeCap.capture(), afterCap.capture(), eq(2L), isNull(), eq("Overcharged"));
        InvoiceDtos.InvoiceSummary after = (InvoiceDtos.InvoiceSummary) afterCap.getValue();
        InvoiceDtos.InvoiceSummary before = (InvoiceDtos.InvoiceSummary) beforeCap.getValue();
        assertEquals(0, before.outstanding().compareTo(new BigDecimal("100.00")));
        assertEquals(0, after.outstanding().compareTo(new BigDecimal("70.00")));
        assertEquals(0, after.activeCreditedTotal().compareTo(new BigDecimal("30.00")));
    }

    @Test
    void fullCoverageByPaymentsAndCreditsTogetherShowsFullyPaid() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "25.00", InvoiceStatus.PARTIALLY_PAID));

        service.issue(42L, new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("75.00"), "Balance write-off"));

        assertEquals(InvoiceStatus.FULLY_PAID, inv.getStatus());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void issueAboveCommitTimeCreditableOutstandingIsRefusedWhollyWithRemainingAmount() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));
        inv.getCreditNotes().add(CreditNote.issue(inv, new BigDecimal("25.00"), "Prior credit", ISSUER));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.issue(42L,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("75.01"), "Too much")));

        // Refusal states the amount still creditable: 100 - 0 - 25 = 75.00.
        assertEquals("Credit note amount exceeds the remaining creditable amount of 75.00",
                ex.getMessage());
        verifyNoInteractions(creditNoteRepository);
        verifyNoInteractions(auditService);
        assertEquals(InvoiceStatus.UNPAID, inv.getStatus());
        verify(invoiceRepository).findByIdForUpdate(42L);
    }

    @Test
    void zeroNegativeAndOverPreciseAmountsAreRefused() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));

        assertEquals("Credit note amount must be greater than zero",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(BigDecimal.ZERO, "r"))).getMessage());
        assertEquals("Credit note amount must be greater than zero",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("-5.00"), "r"))).getMessage());
        assertEquals("Credit note amount must have no more than 2 decimal places",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("10.005"), "r"))).getMessage());
        assertEquals(InvoiceStatus.UNPAID, inv.getStatus());
        verifyNoInteractions(creditNoteRepository);
    }

    @Test
    void blankReasonIsRefused() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));

        assertEquals("A reason is required",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("5.00"), "   "))).getMessage());
        assertEquals("A reason is required",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("5.00"), null))).getMessage());
        verifyNoInteractions(creditNoteRepository);
    }

    @Test
    void cancelledInvoiceRefusesIssuance() {
        givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.CANCELLED));

        assertEquals("Cannot issue a credit note for a cancelled invoice",
                assertThrows(BadRequestException.class, () -> service.issue(42L,
                        new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("5.00"), "r"))).getMessage());
        verifyNoInteractions(creditNoteRepository);
    }

    @Test
    void voidRestoresAmountExactlyOnceAndCancelledInvoiceStaysCancelled() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.PARTIALLY_PAID));
        CreditNote note = CreditNote.issue(inv, new BigDecimal("40.00"), "Damaged goods", ISSUER);
        inv.getCreditNotes().add(note);
        when(creditNoteRepository.findById(501L)).thenReturn(Optional.of(note));

        CreditNoteDtos.CreditNoteResponse res = service.voidNote(42L, 501L);

        assertEquals(CreditNoteStatus.VOIDED, res.status());
        assertEquals(CreditNoteStatus.VOIDED, note.getStatus());
        // Amount restored: outstanding back to the full total.
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("100.00")));
        assertEquals(0, inv.getActiveCreditedTotal().compareTo(new BigDecimal("0.00")));
        assertEquals(InvoiceStatus.UNPAID, inv.getStatus());
        verify(auditService).record(eq("INVOICE"), eq(42L), eq("CREDIT_NOTE_VOIDED"),
                any(), any(), eq(2L), isNull(), eq("Damaged goods"));

        // A second void is refused and leaves everything unchanged.
        assertEquals("Credit note has already been voided",
                assertThrows(BadRequestException.class, () -> service.voidNote(42L, 501L)).getMessage());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void voidOnCancelledInvoiceIsAllowedAndKeepsItCancelled() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.CANCELLED));
        CreditNote note = CreditNote.issue(inv, new BigDecimal("40.00"), "Damaged goods", ISSUER);
        inv.getCreditNotes().add(note);
        when(creditNoteRepository.findById(501L)).thenReturn(Optional.of(note));

        service.voidNote(42L, 501L);

        assertEquals(CreditNoteStatus.VOIDED, note.getStatus());
        assertEquals(InvoiceStatus.CANCELLED, inv.getStatus());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void voidingANoteOfAnotherInvoiceIsNotFound() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));
        Invoice other = invoice(99L, "50.00", "0.00", InvoiceStatus.UNPAID);
        CreditNote note = CreditNote.issue(other, new BigDecimal("10.00"), "Not this invoice", ISSUER);
        when(creditNoteRepository.findById(777L)).thenReturn(Optional.of(note));

        assertThrows(NotFoundException.class, () -> service.voidNote(42L, 777L));
        assertEquals(CreditNoteStatus.ACTIVE, note.getStatus());
    }

    @Test
    void twoSuccessiveSubmissionsCreateTwoNotesEachCheckedAtItsOwnCommitTime() {
        Invoice inv = givenLockedInvoice(invoice("100.00", "0.00", InvoiceStatus.UNPAID));

        service.issue(42L, new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("60.00"), "First"));
        // The second submission is checked against the outstanding left by the first:
        // 100 - 0 - 60 = 40 still creditable, so exactly 40.00 is accepted.
        service.issue(42L, new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("40.00"), "Second"));

        // No de-duplication: two independently valid submissions are two retained notes.
        assertEquals(2, inv.getCreditNotes().size());
        assertEquals(0, inv.getCreditNotes().get(0).getAmount().compareTo(new BigDecimal("60.00")));
        assertEquals(0, inv.getCreditNotes().get(1).getAmount().compareTo(new BigDecimal("40.00")));
        assertEquals(InvoiceStatus.FULLY_PAID, inv.getStatus());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("0.00")));
        verify(auditService, org.mockito.Mockito.times(2)).record(eq("INVOICE"), eq(42L),
                eq("CREDIT_NOTE_ISSUED"), any(), any(), eq(2L), isNull(), any());

        // A third submission is judged against the commit-time creditable amount (now 0.00)
        // and refused wholly, reporting exactly what is still creditable.
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.issue(42L,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("0.01"), "Third")));
        assertEquals("Credit note amount exceeds the remaining creditable amount of 0.00",
                ex.getMessage());
        assertEquals(2, inv.getCreditNotes().size());
        verify(creditNoteRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void voidedNoteCannotTransitionBackToActive() {
        CreditNote note = CreditNote.issue(invoice("10.00", "0.00", InvoiceStatus.UNPAID),
                new BigDecimal("1.00"), "one-way", ISSUER);
        note.markVoided();
        // one-way: a second transition attempt is refused, never a silent no-op
        assertThrows(BadRequestException.class, note::markVoided);
        assertEquals(CreditNoteStatus.VOIDED, note.getStatus());
    }
}
