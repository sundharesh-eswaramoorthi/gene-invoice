package com.geneinvoice.payment;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.creditnote.CreditNoteRepository;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;

    @Transactional
    public Payment record(PaymentDtos.CreatePaymentRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        List<Long> targetIds = new ArrayList<>();
        if (req.invoiceIds() != null && !req.invoiceIds().isEmpty()) {
            targetIds.addAll(req.invoiceIds());
        } else {
            invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customer.getId())
                    .forEach(inv -> targetIds.add(inv.getId()));
        }
        targetIds.sort(Comparator.naturalOrder());

        List<Invoice> targets = new ArrayList<>();
        for (Long invoiceId : targetIds) {
            targets.add(invoiceRepository.findByIdForUpdate(invoiceId)
                    .orElseThrow(() -> new NotFoundException("Invoice not found")));
        }
        for (Invoice inv : targets) {
            if (!inv.getCustomer().getId().equals(customer.getId())) {
                throw new BadRequestException(
                        "Invoice " + inv.getInvoiceNumber() + " does not belong to this customer");
            }
        }

        Payment payment = Payment.builder()
                .customer(customer)
                .amount(req.amount())
                .method(req.method())
                .notes(req.notes())
                .status(PaymentStatus.ACTIVE)
                .build();

        applyTo(payment, targets, req.amount());
        return paymentRepository.save(payment);
    }

    /** Apply `amount` to outstanding invoices oldest-first; leftover goes to credit. */
    private void applyTo(Payment payment, List<Invoice> targets, BigDecimal amount) {
        Customer customer = payment.getCustomer();
        List<Invoice> outstanding = new ArrayList<>(targets.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.FULLY_PAID && i.getStatus() != InvoiceStatus.CANCELLED)
                .sorted(Comparator.comparing(Invoice::getInvoiceDate))
                .toList());

        BigDecimal remaining = amount;
        for (Invoice inv : outstanding) {
            if (remaining.signum() <= 0) break;
            BigDecimal creditedAmount = creditNoteRepository.sumActiveAmountForInvoice(inv.getId());
            BigDecimal balance = InvoiceService.creditAwareOutstanding(inv, creditedAmount);
            if (balance.signum() <= 0) continue;
            BigDecimal toApply = balance.min(remaining);
            inv.setPaidAmount(inv.getPaidAmount().add(toApply));
            InvoiceService.recomputeStatus(inv, creditedAmount);
            invoiceRepository.save(inv);
            payment.getAllocations().add(PaymentAllocation.builder()
                    .payment(payment).invoice(inv).amount(toApply).build());
            remaining = remaining.subtract(toApply);
        }
        payment.setCreditApplied(remaining);
        if (remaining.signum() > 0) {
            customer.setCreditBalance(customer.getCreditBalance().add(remaining));
            customerRepository.save(customer);
        }
    }

    /**
     * Reverse a payment: undo each allocation, remove credit applied, mark VOIDED.
     * Returns the now-voided payment.
     */
    @Transactional
    public Payment voidPayment(Long paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (p.getStatus() == PaymentStatus.VOIDED) {
            throw new BadRequestException("Payment already voided");
        }
        reverseAllocations(p);
        p.setStatus(PaymentStatus.VOIDED);
        return paymentRepository.save(p);
    }

    /** Reverse allocations only (does NOT mark status). Used both for void and for re-recording. */
    private void reverseAllocations(Payment p) {
        List<PaymentAllocation> allocations = new ArrayList<>(p.getAllocations());
        List<Long> invoiceIds = allocations.stream()
                .map(alloc -> alloc.getInvoice().getId())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        Map<Long, Invoice> lockedById = new HashMap<>();
        for (Long invoiceId : invoiceIds) {
            lockedById.put(invoiceId, invoiceRepository.findByIdForUpdate(invoiceId)
                    .orElseThrow(() -> new NotFoundException("Invoice not found")));
        }
        for (PaymentAllocation alloc : allocations) {
            Invoice inv = lockedById.get(alloc.getInvoice().getId());
            inv.setPaidAmount(inv.getPaidAmount().subtract(alloc.getAmount()));
            if (inv.getPaidAmount().signum() < 0) inv.setPaidAmount(BigDecimal.ZERO);
            InvoiceService.recomputeStatus(inv, creditNoteRepository.sumActiveAmountForInvoice(inv.getId()));
            invoiceRepository.save(inv);
        }
        p.getAllocations().clear();
        if (p.getCreditApplied() != null && p.getCreditApplied().signum() > 0) {
            Customer c = p.getCustomer();
            BigDecimal newCredit = c.getCreditBalance().subtract(p.getCreditApplied());
            c.setCreditBalance(newCredit.signum() < 0 ? BigDecimal.ZERO : newCredit);
            customerRepository.save(c);
        }
        p.setCreditApplied(BigDecimal.ZERO);
    }

    /**
     * Change a payment's amount: reverse current allocations, then re-apply the new amount to the
     * customer's outstanding invoices oldest-first.
     */
    @Transactional
    public Payment updateAmount(Long paymentId, BigDecimal newAmount, String method, String notes) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (p.getStatus() == PaymentStatus.VOIDED) {
            throw new BadRequestException("Payment is voided");
        }
        if (newAmount == null || newAmount.signum() <= 0) {
            throw new BadRequestException("Amount must be positive");
        }
        reverseAllocations(p);
        p.setAmount(newAmount);
        if (method != null) p.setMethod(method);
        if (notes != null) p.setNotes(notes);
        List<Long> targetIds = invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(p.getCustomer().getId())
                .stream()
                .map(Invoice::getId)
                .sorted(Comparator.naturalOrder())
                .toList();
        List<Invoice> targets = new ArrayList<>();
        for (Long invoiceId : targetIds) {
            targets.add(invoiceRepository.findByIdForUpdate(invoiceId)
                    .orElseThrow(() -> new NotFoundException("Invoice not found")));
        }
        applyTo(p, targets, newAmount);
        return paymentRepository.save(p);
    }

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
    }

    @Transactional(readOnly = true)
    public List<Payment> list(Long customerId) {
        return customerId == null
                ? paymentRepository.findAll()
                : paymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId);
    }
}
