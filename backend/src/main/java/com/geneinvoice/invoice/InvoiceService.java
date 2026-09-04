package com.geneinvoice.invoice;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.creditnote.CreditNoteRepository;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final CurrentUser currentUser;

    @Transactional
    public Invoice create(InvoiceDtos.CreateInvoiceRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        Invoice invoice = Invoice.builder()
                .customer(customer)
                .invoiceDate(req.invoiceDate() == null ? Instant.now() : req.invoiceDate())
                .notes(req.notes())
                .invoiceNumber(nextInvoiceNumber())
                .build();

        BigDecimal total = BigDecimal.ZERO;
        List<InvoiceItem> items = new ArrayList<>();
        for (InvoiceDtos.LineInput in : req.items()) {
            Product p = productRepository.findById(in.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + in.productId()));
            BigDecimal unitPrice = in.unitPrice() != null ? in.unitPrice() : p.getPrice();
            if (unitPrice.signum() < 0) throw new BadRequestException("Unit price cannot be negative");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(in.quantity()));
            items.add(InvoiceItem.builder()
                    .invoice(invoice).product(p)
                    .quantity(in.quantity()).unitPrice(unitPrice).lineTotal(lineTotal)
                    .build());
            total = total.add(lineTotal);
        }
        invoice.setItems(items);
        invoice.setTotal(total);
        applyCustomerCreditIfAny(invoice);
        recomputeStatus(invoice, BigDecimal.ZERO);
        return invoiceRepository.save(invoice);
    }

    private void applyCustomerCreditIfAny(Invoice invoice) {
        Customer c = invoice.getCustomer();
        BigDecimal credit = c.getCreditBalance();
        if (credit == null || credit.signum() <= 0) return;
        BigDecimal apply = credit.min(invoice.getTotal());
        invoice.setPaidAmount(invoice.getPaidAmount().add(apply));
        c.setCreditBalance(credit.subtract(apply));
        customerRepository.save(c);
    }

    /** Active (non-voided) credited amount of an invoice; zero when the invoice has no active credit notes. */
    public BigDecimal creditedAmount(Long invoiceId) {
        return creditNoteRepository.sumActiveAmountByInvoiceId(invoiceId);
    }

    /**
     * The single shared invoice position: outstanding = total - successful paid amount - active
     * credited amount, floored at zero. The floor is a presentation invariant only - validation
     * decisions use this same value via the serialized lifecycle and refuse rather than clamp.
     */
    public static BigDecimal outstandingOf(Invoice inv, BigDecimal credited) {
        BigDecimal raw = inv.getTotal().subtract(inv.getPaidAmount()).subtract(credited);
        return raw.signum() < 0 ? BigDecimal.ZERO : raw;
    }

    /**
     * Derive paid state from the shared position in both directions; CANCELLED stays authoritative.
     * FULLY_PAID exactly when outstanding reaches zero, PARTIALLY_PAID when successful payments
     * plus active credits are positive, UNPAID otherwise.
     */
    public static void recomputeStatus(Invoice inv, BigDecimal credited) {
        if (inv.getStatus() == InvoiceStatus.CANCELLED) return;
        BigDecimal raw = inv.getTotal().subtract(inv.getPaidAmount()).subtract(credited);
        if (raw.signum() <= 0) inv.setStatus(InvoiceStatus.FULLY_PAID);
        else if (inv.getPaidAmount().add(credited).signum() > 0) inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
        else inv.setStatus(InvoiceStatus.UNPAID);
    }

    private String nextInvoiceNumber() {
        String prefix = "INV-" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long count = invoiceRepository.countByInvoiceNumberStartingWith(prefix);
        return prefix + String.format("%04d", count + 1);
    }

    @Transactional(readOnly = true)
    public Invoice get(Long id) {
        Invoice inv = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
        return inv;
    }

    @Transactional(readOnly = true)
    public Invoice getInternal(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    @Transactional(readOnly = true)
    public List<Invoice> list() {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null) {
            return invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(callerCustomer);
        }
        return invoiceRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Invoice> listByCustomer(Long customerId) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(customerId)) {
            throw new AccessDeniedException("Not allowed");
        }
        return invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customerId);
    }

    @Transactional
    public Invoice cancel(Long id) {
        Invoice inv = getInternal(id);
        if (inv.getStatus() == InvoiceStatus.FULLY_PAID || inv.getPaidAmount().signum() > 0) {
            throw new BadRequestException("Cannot cancel an invoice with payments; refund first");
        }
        inv.setStatus(InvoiceStatus.CANCELLED);
        return invoiceRepository.save(inv);
    }

    /**
     * Cancel an invoice as part of a dispute resolution. Any amount already paid is refunded to
     * the customer's credit balance. Caller (DisputeService) is responsible for audit logging.
     */
    @Transactional
    public Invoice cancelWithRefund(Long id) {
        Invoice inv = getInternal(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        if (inv.getPaidAmount().signum() > 0) {
            Customer c = inv.getCustomer();
            c.setCreditBalance(c.getCreditBalance().add(inv.getPaidAmount()));
            customerRepository.save(c);
            inv.setPaidAmount(BigDecimal.ZERO);
        }
        inv.setStatus(InvoiceStatus.CANCELLED);
        return invoiceRepository.save(inv);
    }

    /**
     * Replace an invoice's line items and recompute the total. If the new total is less than what
     * was already paid, the difference is refunded to the customer's credit balance. Caller
     * (DisputeService) is responsible for audit logging.
     */
    @Transactional
    public Invoice replaceItems(Long id, List<InvoiceDtos.LineInput> newItems, String notes) {
        if (newItems == null || newItems.isEmpty()) {
            throw new BadRequestException("Items must not be empty");
        }
        Invoice inv = getInternal(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot edit a cancelled invoice");
        }

        inv.getItems().clear();
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceDtos.LineInput in : newItems) {
            Product p = productRepository.findById(in.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + in.productId()));
            BigDecimal unitPrice = in.unitPrice() != null ? in.unitPrice() : p.getPrice();
            if (unitPrice.signum() < 0) throw new BadRequestException("Unit price cannot be negative");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(in.quantity()));
            inv.getItems().add(InvoiceItem.builder()
                    .invoice(inv).product(p)
                    .quantity(in.quantity()).unitPrice(unitPrice).lineTotal(lineTotal)
                    .build());
            total = total.add(lineTotal);
        }
        inv.setTotal(total);
        if (notes != null) inv.setNotes(notes);

        if (inv.getPaidAmount().compareTo(total) > 0) {
            BigDecimal refund = inv.getPaidAmount().subtract(total);
            Customer c = inv.getCustomer();
            c.setCreditBalance(c.getCreditBalance().add(refund));
            customerRepository.save(c);
            inv.setPaidAmount(total);
        }
        recomputeStatus(inv, creditedAmount(inv.getId()));
        return invoiceRepository.save(inv);
    }
}
