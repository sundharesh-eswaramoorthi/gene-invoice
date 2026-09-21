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

@Component
@RequiredArgsConstructor
public class CreditLedger {

    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;

    public record CreditMove(PaymentService.InvoicePaymentAudit before,
                             PaymentService.InvoicePaymentAudit after) {}

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

    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    private void lockInvoice(Invoice invoice) {
        if (invoice.getId() != null) invoiceRepository.findByIdForUpdate(invoice.getId());
    }

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
