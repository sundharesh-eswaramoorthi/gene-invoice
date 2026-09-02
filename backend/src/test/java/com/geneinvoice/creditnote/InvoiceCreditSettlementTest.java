package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one derived invoice settlement definition, credit-aware. Outstanding is
 * max(0, total - successful payments - active credit notes); the SAME derived
 * value feeds payment allocation, PARTIALLY_PAID/FULLY_PAID recomputation and
 * every invoice DTO, while credits never become cash (Invoice.paidAmount and
 * Customer.creditBalance are untouched by them) and CANCELLED stays sticky.
 *
 * Expected values are written out literally: every assertion reads the value the
 * running system uses (getBalance, the DTO mappers, the payment allocation) and
 * compares it against the exact expected amount.
 */
class InvoiceCreditSettlementTest {

    private static final Instant DATE_1 = Instant.parse("2024-05-01T10:00:00Z");
    private static final User ISSUER = User.builder().id(2L).username("manager1").build();

    private static Customer customer() {
        return Customer.builder().id(7L).name("Acme Corp")
                .creditBalance(BigDecimal.ZERO).build();
    }

    private static Invoice invoice(Long id, String total, String paid,
                                   InvoiceStatus status, Customer c) {
        return Invoice.builder()
                .id(id)
                .invoiceNumber("INV-" + id)
                .customer(c)
                .invoiceDate(DATE_1)
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build();
    }

    private static CreditNote activeNote(Invoice inv, String amount) {
        CreditNote note = CreditNote.issue(inv, new BigDecimal(amount), "Overcharge", ISSUER);
        inv.getCreditNotes().add(note);
        return note;
    }

    // ------------------------------------------------------------------
    // AC5: outstanding = max(0, T - P - C), derived, never cash.
    // ------------------------------------------------------------------

    @Test
    void outstandingIsTotalMinusPaymentsMinusActiveCreditsFlooredAtZero() {
        Customer c = customer();
        Invoice inv = invoice(1L, "100.00", "30.00", InvoiceStatus.PARTIALLY_PAID, c);
        activeNote(inv, "20.00");
        CreditNote voided = CreditNote.issue(inv, new BigDecimal("15.00"), "Old promo", ISSUER);
        voided.markVoided();
        inv.getCreditNotes().add(voided);

        // activeCredits = 20.00 (the VOIDED 15.00 contributes nothing);
        // outstanding = 100.00 - 30.00 - 20.00 = 50.00.
        assertEquals(0, inv.getActiveCreditedTotal().compareTo(new BigDecimal("20.00")));
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("50.00")));

        // Credits never become cash: paidAmount and the customer's wallet are
        // exactly what they were before the note existed.
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("30.00")));
        assertEquals(0, c.getCreditBalance().compareTo(new BigDecimal("0.00")));

        // The floor: payments 90.00 + credits 20.00 on a 100.00 invoice floor at
        // zero rather than going negative.
        Invoice floored = invoice(2L, "100.00", "90.00", InvoiceStatus.PARTIALLY_PAID, c);
        activeNote(floored, "20.00");
        assertEquals(0, floored.getBalance().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void invoiceDtosExposeActiveCreditedTotalAndTheSameSharedOutstanding() {
        Customer c = customer();
        Invoice inv = invoice(3L, "100.00", "30.00", InvoiceStatus.PARTIALLY_PAID, c);
        activeNote(inv, "20.00");

        InvoiceDtos.InvoiceSummary summary = InvoiceDtos.InvoiceSummary.from(inv);
        assertEquals(0, summary.activeCreditedTotal().compareTo(new BigDecimal("20.00")));
        assertEquals(0, summary.outstanding().compareTo(new BigDecimal("50.00")));
        assertEquals(0, summary.balance().compareTo(new BigDecimal("50.00")));
        assertEquals(0, summary.total().compareTo(new BigDecimal("100.00")));
        assertEquals(0, summary.paidAmount().compareTo(new BigDecimal("30.00")));

        InvoiceDtos.InvoiceDto detail = InvoiceDtos.InvoiceDto.from(inv);
        assertEquals(0, detail.activeCreditedTotal().compareTo(new BigDecimal("20.00")));
        assertEquals(0, detail.outstanding().compareTo(new BigDecimal("50.00")));
        assertEquals(0, detail.balance().compareTo(new BigDecimal("50.00")));
        // Summary and detail agree: one derived outstanding across reads.
        assertEquals(0, detail.outstanding().compareTo(summary.outstanding()));
        assertEquals(0, detail.activeCreditedTotal().compareTo(summary.activeCreditedTotal()));
    }

    @Test
    void statusRecomputationCoversPaymentsAndCreditsTogether() {
        Customer c = customer();

        // Full coverage by P and C together (60.00 + 40.00 of 100.00) is FULLY_PAID.
        Invoice full = invoice(4L, "100.00", "60.00", InvoiceStatus.PARTIALLY_PAID, c);
        activeNote(full, "40.00");
        InvoiceService.recomputeStatus(full);
        assertEquals(InvoiceStatus.FULLY_PAID, full.getStatus());

        // Credits alone (no payment at all) with outstanding remaining is
        // PARTIALLY_PAID, not UNPAID.
        Invoice creditsOnly = invoice(5L, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        activeNote(creditsOnly, "10.00");
        InvoiceService.recomputeStatus(creditsOnly);
        assertEquals(InvoiceStatus.PARTIALLY_PAID, creditsOnly.getStatus());

        // No coverage at all stays UNPAID.
        Invoice none = invoice(6L, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        InvoiceService.recomputeStatus(none);
        assertEquals(InvoiceStatus.UNPAID, none.getStatus());
    }

    @Test
    void paymentAllocationCapsAtTheOutstandingAfterCredits() {
        Customer c = customer();
        Invoice inv = invoice(7L, "100.00", "0.00", InvoiceStatus.PARTIALLY_PAID, c);
        activeNote(inv, "20.00"); // outstanding is 80.00

        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        PaymentService payments =
                new PaymentService(paymentRepository, customerRepository, invoiceRepository);

        when(customerRepository.findById(7L)).thenReturn(Optional.of(c));
        when(invoiceRepository.findAllByIdInForUpdate(List.of(7L))).thenReturn(List.of(inv));
        when(paymentRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        Payment p = payments.record(new PaymentDtos.CreatePaymentRequest(
                7L, new BigDecimal("90.00"), "cash", null, List.of(7L)));

        // Only the credit-reduced outstanding (100.00 - 20.00 = 80.00) is a
        // payment target; the remaining 10.00 becomes customer credit.
        assertEquals(1, p.getAllocations().size());
        assertEquals(0, p.getAllocations().get(0).getAmount().compareTo(new BigDecimal("80.00")));
        assertEquals(0, p.getCreditApplied().compareTo(new BigDecimal("10.00")));
        assertEquals(0, c.getCreditBalance().compareTo(new BigDecimal("10.00")));
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("80.00")));
        // Full coverage by payments (80.00) and credits (20.00) together.
        assertEquals(InvoiceStatus.FULLY_PAID, inv.getStatus());
    }

    // ------------------------------------------------------------------
    // AC6: cancelled invoices refuse issuance; existing notes still void.
    // ------------------------------------------------------------------

    @Test
    void cancelledInvoiceRefusesNewCreditNoteIssuance() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        CreditNoteRepository noteRepo = mock(CreditNoteRepository.class);
        AuditService audit = mock(AuditService.class);
        CurrentUser cu = mock(CurrentUser.class);
        CreditNoteCommandService credits = new CreditNoteCommandService(invRepo, noteRepo, audit, cu);

        Customer c = customer();
        Invoice cancelled = invoice(8L, "100.00", "0.00", InvoiceStatus.CANCELLED, c);
        when(invRepo.findByIdForUpdate(8L)).thenReturn(Optional.of(cancelled));

        assertThrows(BadRequestException.class, () -> credits.issue(8L,
                new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("25.00"), "Overcharge")));

        // The refusal leaves the invoice exactly as it was.
        assertEquals(0, cancelled.getCreditNotes().size());
        assertEquals(InvoiceStatus.CANCELLED, cancelled.getStatus());
        assertEquals(0, cancelled.getBalance().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void voidingAnActiveNoteOnACancelledInvoiceRestoresItsAmountAndKeepsCancelled() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        CreditNoteRepository noteRepo = mock(CreditNoteRepository.class);
        AuditService audit = mock(AuditService.class);
        CurrentUser cu = mock(CurrentUser.class);
        CreditNoteCommandService credits = new CreditNoteCommandService(invRepo, noteRepo, audit, cu);
        when(cu.require()).thenReturn(ISSUER);
        when(noteRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        Customer c = customer();
        Invoice cancelled = invoice(9L, "100.00", "0.00", InvoiceStatus.CANCELLED, c);
        CreditNote note = activeNote(cancelled, "20.00"); // outstanding was 80.00
        when(invRepo.findByIdForUpdate(9L)).thenReturn(Optional.of(cancelled));
        when(noteRepo.findById(501L)).thenReturn(Optional.of(note));

        credits.voidNote(9L, 501L);

        // The note stays present and plainly voided, and its exact 20.00 is
        // restored to the outstanding: 80.00 -> 100.00.
        assertEquals(CreditNoteStatus.VOIDED, note.getStatus());
        assertEquals(1, cancelled.getCreditNotes().size());
        assertEquals(0, cancelled.getActiveCreditedTotal().compareTo(new BigDecimal("0.00")));
        assertEquals(0, cancelled.getBalance().compareTo(new BigDecimal("100.00")));
        // CANCELLED is sticky: voiding never resurrects the invoice.
        assertEquals(InvoiceStatus.CANCELLED, cancelled.getStatus());
        // And no cash moved: no payment was ever recorded and the wallet is untouched.
        assertEquals(0, cancelled.getPaidAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, c.getCreditBalance().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void voidingTwiceIsRefusedAndChangesNothing() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        CreditNoteRepository noteRepo = mock(CreditNoteRepository.class);
        AuditService audit = mock(AuditService.class);
        CurrentUser cu = mock(CurrentUser.class);
        CreditNoteCommandService credits = new CreditNoteCommandService(invRepo, noteRepo, audit, cu);

        Customer c = customer();
        Invoice inv = invoice(10L, "100.00", "0.00", InvoiceStatus.PARTIALLY_PAID, c);
        CreditNote note = activeNote(inv, "20.00");
        note.markVoided();
        when(invRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inv));
        when(noteRepo.findById(502L)).thenReturn(Optional.of(note));

        assertThrows(BadRequestException.class, () -> credits.voidNote(10L, 502L));
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("100.00")));
        assertEquals(InvoiceStatus.PARTIALLY_PAID, inv.getStatus());
    }
}

