package com.geneinvoice.invoice;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.Expression;
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

    public static final String ENTITY = "INVOICE";

    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CurrentUser currentUser;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final PaymentPromiseService promiseService;

    @Transactional
    public Invoice create(InvoiceDtos.CreateInvoiceRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        // Mandatory on create, enforced here so every caller obeys it — not just the form (AC-A2).
        User salesPoc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES);

        Invoice invoice = Invoice.builder()
                .customer(customer)
                .invoiceDate(req.invoiceDate() == null ? Instant.now() : req.invoiceDate())
                .notes(req.notes())
                .invoiceNumber(nextInvoiceNumber())
                .salesPoc(salesPoc)
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
        recomputeStatus(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        // Leftover credit is the only thing that can have paid a brand-new invoice, so a non-zero
        // paid amount here is exactly the credit it consumed.
        BigDecimal creditUsed = saved.getPaidAmount();
        auditService.record(ENTITY, saved.getId(), "INVOICE_CREATED", null,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.idOrNull(), null,
                creditUsed.signum() > 0 ? "Customer credit applied: " + creditUsed.toPlainString() : null);

        pocService.notifyAssignee(salesPoc, PocType.SALES,
                "invoice " + saved.getInvoiceNumber(), "/invoices/" + saved.getId());
        promiseService.reevaluateForCustomer(customer.getId());
        return saved;
    }

    /** Inline edit from the detail screen: notes and the Sales POC. Line items stay dispute-only. */
    @Transactional
    public Invoice update(Long id, InvoiceDtos.UpdateInvoiceRequest req) {
        Invoice inv = getInternal(id);
        Object before = InvoiceDtos.InvoiceDto.from(inv);

        if (req.notes() != null) inv.setNotes(req.notes());
        if (req.salesPocUserId() != null) {
            User poc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES);
            User previous = inv.getSalesPoc();
            if (previous == null || !previous.getId().equals(poc.getId())) {
                inv.setSalesPoc(poc);
                pocService.notifyAssignee(poc, PocType.SALES,
                        "invoice " + inv.getInvoiceNumber(), "/invoices/" + inv.getId());
            }
        }
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_UPDATED", before,
                InvoiceDtos.InvoiceDto.from(saved),
                currentUser.require().getId(), null, null);
        return saved;
    }

    /** Reassigns just the Sales POC. Used by the inline row action and the bulk action. */
    @Transactional
    public Invoice reassignSalesPoc(Long id, Long userId) {
        return update(id, new InvoiceDtos.UpdateInvoiceRequest(null, userId));
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

    public static void recomputeStatus(Invoice inv) {
        if (inv.getStatus() == InvoiceStatus.CANCELLED) return;
        BigDecimal balance = inv.getBalance();
        if (balance.signum() <= 0) inv.setStatus(InvoiceStatus.FULLY_PAID);
        else if (inv.getPaidAmount().signum() > 0) inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
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

    // ---- list, tiles ------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceSummary> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        boolean poc = scopeResolver.canSeePoc();
        var page = queryExecutor.run(Invoice.class, TableSchemas.INVOICES, query,
                scope.predicates(), List.of("customer", "salesPoc"));
        return PageResponse.of(
                page.content().stream().map(i -> InvoiceDtos.InvoiceSummary.from(i, poc)).toList(),
                query, page.total(), scope.lockedFilters());
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        return queryExecutor.ids(Invoice.class, TableSchemas.INVOICES, query,
                scopeResolver.forInvoices().predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Invoice> allMatching(TableQuery query) {
        return queryExecutor.run(Invoice.class, TableSchemas.INVOICES, query,
                scopeResolver.forInvoices().predicates(), List.of("customer", "salesPoc")).content();
    }

    /** Tiles computed over the whole filtered set, never from the current page (AC-E1). */
    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceSummaryTiles tiles(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        Object[] row = queryExecutor.aggregate(Invoice.class, TableSchemas.INVOICES, query,
                scope.predicates(), (root, q, cb) -> {
                    Expression<BigDecimal> total = root.get("total");
                    Expression<BigDecimal> paid = root.get("paidAmount");
                    Expression<BigDecimal> liveBalance = cb.<BigDecimal>selectCase()
                            .when(cb.equal(root.get("status"), InvoiceStatus.CANCELLED),
                                    cb.literal(BigDecimal.ZERO))
                            .otherwise(cb.diff(total, paid));
                    return List.of(
                            cb.count(root.get("id")),
                            cb.coalesce(cb.sum(total), BigDecimal.ZERO),
                            cb.coalesce(cb.sum(paid), BigDecimal.ZERO),
                            cb.coalesce(cb.sum(liveBalance), BigDecimal.ZERO),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), InvoiceStatus.UNPAID)),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), InvoiceStatus.PARTIALLY_PAID)),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), InvoiceStatus.FULLY_PAID)),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), InvoiceStatus.CANCELLED)),
                            Aggregates.countWhen(cb, cb.isNull(root.get("salesPoc"))));
                });
        return new InvoiceDtos.InvoiceSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asMoney(row[3]),
                Aggregates.asLong(row[4]), Aggregates.asLong(row[5]), Aggregates.asLong(row[6]),
                Aggregates.asLong(row[7]), Aggregates.asLong(row[8]));
    }

    @Transactional
    public Invoice cancel(Long id) {
        Invoice inv = getInternal(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        if (inv.getStatus() == InvoiceStatus.FULLY_PAID || inv.getPaidAmount().signum() > 0) {
            throw new BadRequestException("Cannot cancel an invoice with payments; refund first");
        }
        Object before = InvoiceDtos.InvoiceDto.from(inv);
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_CANCELLED", before,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.require().getId(), null, null);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
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
        Invoice saved = invoiceRepository.save(inv);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
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
        recomputeStatus(inv);
        Invoice saved = invoiceRepository.save(inv);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }
}
