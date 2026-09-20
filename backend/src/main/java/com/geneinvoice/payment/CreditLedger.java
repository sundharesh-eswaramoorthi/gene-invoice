package com.geneinvoice.payment;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Customer credit is money that still belongs to the payments it came from: an overpayment, or a
 * refund of money a payment had put on an invoice. Every movement into or out of credit is booked
 * against its payment, so an active payment always accounts for its whole amount —
 * {@code amount = sum(allocations) + creditApplied}, where {@code creditApplied} is the part still
 * sitting in the customer's credit balance. Voiding a payment can then take back exactly what it put
 * in, wherever the money went since.
 *
 * <p>Credit from before this ledger has no payment behind it. It is still spent, just untraced.
 */
@Component
@RequiredArgsConstructor
public class CreditLedger {

    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;

    /** One payment's credit landing on an invoice, with the invoice's position either side. */
    public record CreditMove(PaymentService.InvoicePaymentAudit before,
                             PaymentService.InvoicePaymentAudit after) {}

    /**
     * Pays what it can of a saved invoice from its customer's credit, oldest payment's credit first,
     * booking each part as an allocation of the payment it came from. Returns those parts for the
     * caller to audit.
     */
    public List<CreditMove> applyTo(Invoice invoice) {
        // Spending credit moves the customer's balance and the invoice's paid amount, so it takes
        // the same two locks in the same order as every other money path — the customer, then the
        // invoice — and reads both back under them (PPD-01).
        Customer customer = lockCustomer(invoice.getCustomer().getId());
        lockInvoice(invoice);
        BigDecimal credit = customer.getCreditBalance();
        if (credit == null || credit.signum() <= 0 || invoice.getBalance().signum() <= 0) {
            return List.of();
        }
        BigDecimal apply = credit.min(invoice.getBalance());

        List<CreditMove> moves = new ArrayList<>();
        BigDecimal remaining = apply;
        for (Payment p : creditHolders(customer.getId())) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = p.getCreditApplied().min(remaining);
            PaymentService.InvoicePaymentAudit before = position(p, take, invoice);
            p.setCreditApplied(p.getCreditApplied().subtract(take));
            p.getAllocations().add(PaymentAllocation.builder()
                    .payment(p).invoice(invoice).amount(take).build());
            invoice.setPaidAmount(invoice.getPaidAmount().add(take));
            InvoiceService.recomputeStatus(invoice);
            moves.add(new CreditMove(before, position(p, take, invoice)));
            remaining = remaining.subtract(take);
        }
        invoice.setPaidAmount(invoice.getPaidAmount().add(remaining));
        customer.setCreditBalance(credit.subtract(apply));
        customerRepository.save(customer);
        return moves;
    }

    /**
     * Moves {@code amount} of what an invoice has been paid back into its customer's credit, newest
     * payment first, keeping the money booked to the payments it came from. The caller lowers the
     * invoice's paid amount.
     */
    public void refund(Invoice invoice, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        Customer locked = lockCustomer(invoice.getCustomer().getId());
        lockInvoice(invoice);
        List<PaymentAllocation> standing =
                new ArrayList<>(allocationRepository.findByInvoiceIdWithPayment(invoice.getId()));
        standing.sort(Comparator.comparing((PaymentAllocation a) -> a.getPayment().getPaidAt())
                .thenComparing(PaymentAllocation::getId)
                .reversed());

        BigDecimal remaining = amount;
        for (PaymentAllocation a : standing) {
            if (remaining.signum() <= 0) break;
            Payment p = a.getPayment();
            BigDecimal take = a.getAmount().min(remaining);
            a.setAmount(a.getAmount().subtract(take));
            if (a.getAmount().signum() == 0) p.getAllocations().remove(a);
            p.setCreditApplied(p.getCreditApplied().add(take));
            remaining = remaining.subtract(take);
        }
        locked.setCreditBalance(locked.getCreditBalance().add(amount));
        customerRepository.save(locked);
    }

    /** The customer, locked for the rest of the transaction: the first lock of every money path. */
    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    /** The invoice, locked after its customer — the one order the whole money path takes. */
    private void lockInvoice(Invoice invoice) {
        if (invoice.getId() != null) invoiceRepository.findByIdForUpdate(invoice.getId());
    }

    /** Active payments of the customer that still have money sitting in credit, oldest first. */
    private List<Payment> creditHolders(Long customerId) {
        return paymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.ACTIVE && p.getCreditApplied().signum() > 0)
                .sorted(Comparator.comparing(Payment::getPaidAt).thenComparing(Payment::getId))
                .toList();
    }

    private static PaymentService.InvoicePaymentAudit position(Payment p, BigDecimal amount, Invoice inv) {
        return new PaymentService.InvoicePaymentAudit(p.getId(), amount, inv.getPaidAmount(),
                inv.getBalance(), inv.getStatus());
    }
}
