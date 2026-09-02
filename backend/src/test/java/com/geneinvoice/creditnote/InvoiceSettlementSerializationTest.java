package com.geneinvoice.creditnote;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.user.User;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The canonical invoice-mutation serialization boundary. Every financial writer
 * that reads an invoice's total, paidAmount, credit contribution or settlement
 * status in order to change it must load that invoice through a PESSIMISTIC_WRITE
 * repository query FIRST, and every multi-invoice acquisition must happen in the
 * canonical invoiceDate-then-id order, while payment allocation itself stays
 * oldest-first. The repository-level tests assert the lock mode and the ordering
 * clause by their exact text; the service-level tests pin, per entry point, that
 * the lock query runs before any mutation of an invoice row.
 */
class InvoiceSettlementSerializationTest {

    private static final Instant DATE_1 = Instant.parse("2024-05-01T10:00:00Z");
    private static final Instant DATE_2 = Instant.parse("2024-05-02T10:00:00Z");

    private static Customer customer() {
        return Customer.builder().id(7L).name("Acme Corp")
                .creditBalance(BigDecimal.ZERO).build();
    }

    private static Invoice invoice(Long id, Instant date, String total, String paid,
                                   InvoiceStatus status, Customer c) {
        return Invoice.builder()
                .id(id)
                .invoiceNumber("INV-" + id)
                .customer(c)
                .invoiceDate(date)
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build();
    }

    // ------------------------------------------------------------------
    // Repository: lock mode and canonical acquisition order, by exact text.
    // ------------------------------------------------------------------

    @Test
    void singleInvoiceLockQueryIsPessimisticWrite() throws Exception {
        Method m = InvoiceRepository.class.getMethod("findByIdForUpdate", Long.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, m.getAnnotation(Lock.class).value());
        assertEquals("select i from Invoice i where i.id = :id",
                m.getAnnotation(Query.class).value());
    }

    @Test
    void bulkInvoiceLockQueriesArePessimisticWriteInCanonicalInvoiceDateThenIdOrder() throws Exception {
        Method byCustomer = InvoiceRepository.class.getMethod("findByCustomerIdForUpdate", Long.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, byCustomer.getAnnotation(Lock.class).value());
        assertEquals("select i from Invoice i where i.customer.id = :customerId order by i.invoiceDate, i.id",
                byCustomer.getAnnotation(Query.class).value());

        Method byIds = InvoiceRepository.class.getMethod("findAllByIdInForUpdate", Collection.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, byIds.getAnnotation(Lock.class).value());
        assertEquals("select i from Invoice i where i.id in :ids order by i.invoiceDate, i.id",
                byIds.getAnnotation(Query.class).value());
    }

    // ------------------------------------------------------------------
    // Payment writers.
    // ------------------------------------------------------------------

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final PaymentService payments =
            new PaymentService(paymentRepository, customerRepository, invoiceRepository);

    @Test
    void paymentRecordLocksRequestedInvoicesBeforeAllocationAndStaysOldestFirst() {
        Customer c = customer();
        when(customerRepository.findById(7L)).thenReturn(Optional.of(c));
        Invoice early = invoice(101L, DATE_1, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        Invoice late = invoice(102L, DATE_2, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        // The request lists ids in reverse canonical order; the lock query is what
        // canonicalises them (invoiceDate, then id).
        when(invoiceRepository.findAllByIdInForUpdate(List.of(102L, 101L)))
                .thenReturn(List.of(early, late));
        when(paymentRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        Payment p = payments.record(new PaymentDtos.CreatePaymentRequest(
                7L, new BigDecimal("150.00"), "cash", null, List.of(102L, 101L)));

        // The lock acquisition (and no unlocked invoice read) precedes every
        // mutation of an invoice row.
        InOrder order = inOrder(invoiceRepository);
        order.verify(invoiceRepository).findAllByIdInForUpdate(List.of(102L, 101L));
        order.verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));
        verify(invoiceRepository, never()).findById(any());

        // Oldest-first payment allocation order is preserved: the older-dated
        // invoice is settled in full before the newer one receives anything.
        assertEquals(2, p.getAllocations().size());
        assertEquals(101L, p.getAllocations().get(0).getInvoice().getId());
        assertEquals(0, p.getAllocations().get(0).getAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(102L, p.getAllocations().get(1).getInvoice().getId());
        assertEquals(0, p.getAllocations().get(1).getAmount().compareTo(new BigDecimal("50.00")));
        assertEquals(InvoiceStatus.FULLY_PAID, early.getStatus());
        assertEquals(InvoiceStatus.PARTIALLY_PAID, late.getStatus());
        assertEquals(0, p.getCreditApplied().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void paymentRecordWithoutExplicitIdsLocksTheWholeCustomerInvoiceSet() {
        Customer c = customer();
        when(customerRepository.findById(7L)).thenReturn(Optional.of(c));
        Invoice only = invoice(101L, DATE_1, "60.00", "0.00", InvoiceStatus.UNPAID, c);
        when(invoiceRepository.findByCustomerIdForUpdate(7L)).thenReturn(List.of(only));
        when(paymentRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        payments.record(new PaymentDtos.CreatePaymentRequest(
                7L, new BigDecimal("60.00"), "cash", null, null));

        InOrder order = inOrder(invoiceRepository);
        order.verify(invoiceRepository).findByCustomerIdForUpdate(7L);
        order.verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));
        verify(invoiceRepository, never()).findAllByIdInForUpdate(any());
        verify(invoiceRepository, never()).findById(any());
        assertEquals(InvoiceStatus.FULLY_PAID, only.getStatus());
        assertEquals(0, only.getBalance().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void paymentVoidLocksEveryAllocatedInvoiceBeforeReversing() {
        Customer c = customer();
        Invoice inv1 = invoice(101L, DATE_1, "100.00", "30.00", InvoiceStatus.PARTIALLY_PAID, c);
        Invoice inv2 = invoice(102L, DATE_2, "100.00", "20.00", InvoiceStatus.PARTIALLY_PAID, c);
        Payment p = Payment.builder().id(55L).customer(c)
                .amount(new BigDecimal("50.00")).status(PaymentStatus.ACTIVE).build();
        p.getAllocations().add(PaymentAllocation.builder()
                .payment(p).invoice(inv1).amount(new BigDecimal("30.00")).build());
        p.getAllocations().add(PaymentAllocation.builder()
                .payment(p).invoice(inv2).amount(new BigDecimal("20.00")).build());
        when(paymentRepository.findById(55L)).thenReturn(Optional.of(p));
        when(invoiceRepository.findAllByIdInForUpdate(any())).thenReturn(List.of(inv1, inv2));
        when(paymentRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        Payment voided = payments.voidPayment(55L);

        // ALL allocation invoice ids are locked, in one canonical-order query,
        // before a single allocation is reversed.
        ArgumentCaptor<Collection<Long>> idsCap = ArgumentCaptor.forClass(Collection.class);
        InOrder order = inOrder(invoiceRepository);
        order.verify(invoiceRepository).findAllByIdInForUpdate(idsCap.capture());
        order.verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));
        assertEquals(Set.of(101L, 102L), new HashSet<>(idsCap.getValue()));

        // Reversal runs under those locks and marks the payment voided.
        assertEquals(PaymentStatus.VOIDED, voided.getStatus());
        assertTrue(p.getAllocations().isEmpty());
        assertEquals(0, inv1.getPaidAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, inv2.getPaidAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(InvoiceStatus.UNPAID, inv1.getStatus());
        assertEquals(InvoiceStatus.UNPAID, inv2.getStatus());
    }

    @Test
    void paymentAmountUpdateLocksTheCompleteCustomerInvoiceSetBeforeReversal() {
        Customer c = customer();
        Invoice inv = invoice(101L, DATE_1, "100.00", "40.00", InvoiceStatus.PARTIALLY_PAID, c);
        Payment p = Payment.builder().id(56L).customer(c)
                .amount(new BigDecimal("40.00")).status(PaymentStatus.ACTIVE).build();
        p.getAllocations().add(PaymentAllocation.builder()
                .payment(p).invoice(inv).amount(new BigDecimal("40.00")).build());
        when(paymentRepository.findById(56L)).thenReturn(Optional.of(p));
        // The ENTIRE customer set is locked once, before reversal — never
        // overlapping subsets in phases.
        when(invoiceRepository.findByCustomerIdForUpdate(7L)).thenReturn(List.of(inv));
        when(paymentRepository.save(any())).thenAnswer(a -> a.getArgument(0));

        Payment updated = payments.updateAmount(56L, new BigDecimal("100.00"), null, null);

        verify(invoiceRepository, never()).findAllByIdInForUpdate(any());
        verify(invoiceRepository, never()).findById(any());
        InOrder order = inOrder(invoiceRepository);
        order.verify(invoiceRepository).findByCustomerIdForUpdate(7L);
        order.verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));

        // Reversed (40 -> 0) and re-applied (0 -> 100) against the locked set.
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(InvoiceStatus.FULLY_PAID, inv.getStatus());
        assertEquals(1, updated.getAllocations().size());
        assertEquals(0, updated.getAllocations().get(0).getAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(0, updated.getAmount().compareTo(new BigDecimal("100.00")));
    }

    // ------------------------------------------------------------------
    // Invoice writers: cancellation, refund cancellation, item replacement.
    // ------------------------------------------------------------------

    private final InvoiceRepository serviceInvoices = mock(InvoiceRepository.class);
    private final CustomerRepository serviceCustomers = mock(CustomerRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final InvoiceService invoiceService =
            new InvoiceService(serviceInvoices, serviceCustomers, products, currentUser);

    @Test
    void cancellationLoadsThroughTheWriteLockedQueryBeforeMutating() {
        Customer c = customer();
        Invoice inv = invoice(42L, DATE_1, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        when(serviceInvoices.findByIdForUpdate(42L)).thenReturn(Optional.of(inv));
        when(serviceInvoices.save(any())).thenAnswer(a -> a.getArgument(0));

        invoiceService.cancel(42L);

        InOrder order = inOrder(serviceInvoices);
        order.verify(serviceInvoices).findByIdForUpdate(42L);
        order.verify(serviceInvoices).save(inv);
        verify(serviceInvoices, never()).findById(any());
        assertEquals(InvoiceStatus.CANCELLED, inv.getStatus());
    }

    @Test
    void refundCancellationLoadsThroughTheWriteLockedQueryBeforeMutating() {
        Customer c = customer();
        Invoice inv = invoice(42L, DATE_1, "100.00", "40.00", InvoiceStatus.PARTIALLY_PAID, c);
        when(serviceInvoices.findByIdForUpdate(42L)).thenReturn(Optional.of(inv));
        when(serviceInvoices.save(any())).thenAnswer(a -> a.getArgument(0));

        invoiceService.cancelWithRefund(42L);

        InOrder order = inOrder(serviceInvoices);
        order.verify(serviceInvoices).findByIdForUpdate(42L);
        order.verify(serviceInvoices).save(inv);
        verify(serviceInvoices, never()).findById(any());
        // Cash-only refund: paidAmount goes back to the wallet, untouched by credits.
        assertEquals(InvoiceStatus.CANCELLED, inv.getStatus());
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, c.getCreditBalance().compareTo(new BigDecimal("40.00")));
    }

    @Test
    void itemReplacementLoadsThroughTheWriteLockedQueryBeforeMutating() {
        Customer c = customer();
        Invoice inv = invoice(42L, DATE_1, "40.00", "20.00", InvoiceStatus.PARTIALLY_PAID, c);
        Product prod = Product.builder().id(9L).name("Widget").price(new BigDecimal("10.00")).build();
        when(serviceInvoices.findByIdForUpdate(42L)).thenReturn(Optional.of(inv));
        when(serviceInvoices.save(any())).thenAnswer(a -> a.getArgument(0));
        when(products.findById(9L)).thenReturn(Optional.of(prod));

        invoiceService.replaceItems(42L, List.of(new InvoiceDtos.LineInput(9L, 3, null)), "repriced");

        InOrder order = inOrder(serviceInvoices);
        order.verify(serviceInvoices).findByIdForUpdate(42L);
        order.verify(serviceInvoices).save(inv);
        verify(serviceInvoices, never()).findById(any());
        // New total recomputed under the lock; 20 paid of 30 stays partially paid.
        assertEquals(0, inv.getTotal().compareTo(new BigDecimal("30.00")));
        assertEquals(0, inv.getPaidAmount().compareTo(new BigDecimal("20.00")));
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("10.00")));
        assertEquals(InvoiceStatus.PARTIALLY_PAID, inv.getStatus());
    }

    // ------------------------------------------------------------------
    // Credit-note commands lock their one owning invoice.
    // ------------------------------------------------------------------

    @Test
    void creditIssueAndVoidLockTheOwningInvoiceBeforeAnyMutation() {
        InvoiceRepository invRepo = mock(InvoiceRepository.class);
        CreditNoteRepository noteRepo = mock(CreditNoteRepository.class);
        AuditService audit = mock(AuditService.class);
        CurrentUser cu = mock(CurrentUser.class);
        CreditNoteCommandService credits = new CreditNoteCommandService(invRepo, noteRepo, audit, cu);
        User issuer = User.builder().id(2L).username("manager1").build();
        when(cu.require()).thenReturn(issuer);
        when(noteRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        Customer c = customer();
        Invoice inv = invoice(42L, DATE_1, "100.00", "0.00", InvoiceStatus.UNPAID, c);
        when(invRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(inv));

        credits.issue(42L, new CreditNoteDtos.IssueCreditNoteRequest(
                new BigDecimal("25.00"), "Overcharge"));

        // Issuance locks the invoice BEFORE the commit-time creditable recheck and
        // before the note insertion / status recomputation mutate anything.
        InOrder issueOrder = inOrder(invRepo);
        issueOrder.verify(invRepo).findByIdForUpdate(42L);
        issueOrder.verify(invRepo).save(inv);
        verify(invRepo, never()).findById(any());
        assertEquals(0, inv.getBalance().compareTo(new BigDecimal("75.00")));

        // Void locks the same owning invoice before touching the note or the
        // restored outstanding, so competing voids serialize on the invoice.
        Invoice inv2 = invoice(43L, DATE_1, "100.00", "0.00", InvoiceStatus.PARTIALLY_PAID, c);
        CreditNote note = CreditNote.issue(inv2, new BigDecimal("40.00"), "Damaged goods", issuer);
        inv2.getCreditNotes().add(note);
        when(invRepo.findByIdForUpdate(43L)).thenReturn(Optional.of(inv2));
        when(noteRepo.findById(501L)).thenReturn(Optional.of(note));

        credits.voidNote(43L, 501L);

        InOrder voidOrder = inOrder(invRepo);
        voidOrder.verify(invRepo).findByIdForUpdate(43L);
        voidOrder.verify(invRepo).save(inv2);
        assertEquals(CreditNoteStatus.VOIDED, note.getStatus());
        assertEquals(0, inv2.getBalance().compareTo(new BigDecimal("100.00")));
    }
}
