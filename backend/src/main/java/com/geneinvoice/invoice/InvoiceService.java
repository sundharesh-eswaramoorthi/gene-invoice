package com.geneinvoice.invoice;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.automation.AutomationEntityType;
import com.geneinvoice.automation.AutomationEvents;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.payment.CreditLedger;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Expression;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final AutomationEvents automationEvents;
    private final PaymentPromiseService promiseService;
    private final CreditLedger creditLedger;
    private final InvoiceNumbers invoiceNumbers;
    private final UserRepository userRepository;
    private final InvoiceProperties invoiceProperties;

    @Transactional
    public Invoice create(InvoiceDtos.CreateInvoiceRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        // Mandatory on create, enforced here so every caller obeys it — not just the form (AC-A2).
        User salesPoc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES);

        Instant invoiceDate = req.invoiceDate() == null ? Instant.now() : req.invoiceDate();
        Due due = due(customer, InvoiceDates.dayOf(invoiceDate), req.dueDate(), req.paymentTerm());

        Invoice invoice = Invoice.builder()
                .customer(customer)
                .invoiceDate(invoiceDate)
                .dueDate(due.date())
                .paymentTerm(due.term())
                .notes(req.notes())
                // Drawn before this transaction writes anything; see InvoiceNumbers#next.
                .invoiceNumber(invoiceNumbers.next())
                .salesPoc(salesPoc)
                .build();

        List<InvoiceItem> items = buildLines(invoice, req.items());
        invoice.setItems(items);
        invoice.setTotal(totalOf(items));
        // Saved before any credit is booked against it: an allocation needs the invoice's id.
        Invoice saved = invoiceRepository.save(invoice);
        List<CreditLedger.CreditMove> fromCredit = creditLedger.applyTo(saved);
        recomputeStatus(saved);
        saved = invoiceRepository.save(saved);
        // Leftover credit is the only thing that can have paid a brand-new invoice, so a non-zero
        // paid amount here is exactly the credit it consumed.
        BigDecimal creditUsed = saved.getPaidAmount();
        auditService.record(ENTITY, saved.getId(), "INVOICE_CREATED", null,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.idOrNull(), null,
                creditUsed.signum() > 0 ? "Customer credit applied: " + creditUsed.toPlainString() : null);
        for (CreditLedger.CreditMove m : fromCredit) {
            auditService.record(ENTITY, saved.getId(), "PAYMENT_APPLIED", m.before(), m.after(),
                    currentUser.idOrNull(), null, "Paid from customer credit");
        }

        pocService.notifyAssignee(salesPoc, PocType.SALES,
                "invoice " + saved.getInvoiceNumber(), "/invoices/" + saved.getId());
        promiseService.reevaluateForCustomer(customer.getId());
        // One insert, inside this transaction, so a rule sees a record that exists and
        // nothing is lost if the consumer is down. No rule is read here (R3).
        automationEvents.recordCreated(AutomationEntityType.INVOICE, saved.getId());
        return saved;
    }

    /** Inline edit from the detail screen: notes and the Sales POC. Line items stay dispute-only. */
    @Transactional
    public Invoice update(Long id, InvoiceDtos.UpdateInvoiceRequest req) {
        // Read under the row lock, so simultaneous edits queue instead of all reading the same
        // starting point: the before-snapshot below is then the value the previous edit left, and
        // the History tab reads as a chain rather than as several moves from one date (INV-1).
        Invoice inv = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireInBook(id);
        // A cancelled invoice is a dead record: its terms and its collections deadline are no more
        // editable than its lines are (INV-3, and replaceItems below).
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot edit a cancelled invoice");
        }
        // A save composed against an older version of this invoice is refused rather than applied
        // over the top of what has been saved since; the caller is told to reload (UI-09). A
        // caller that sends no version gets no precondition.
        if (req.version() != null && !req.version().equals(inv.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Invoice " + id + " changed since it was read");
        }
        InvoiceDtos.InvoiceDto before = InvoiceDtos.InvoiceDto.from(inv);

        if (req.notes() != null) inv.setNotes(req.notes());
        // The due date moves only when this request asks it to: changing the customer's terms
        // never reaches an invoice that has already been issued (D1, AC-A2).
        Due movedTo = null;
        if (req.dueDate() != null || req.paymentTerm() != null) {
            Due due = due(inv.getCustomer(), InvoiceDates.dayOf(inv.getInvoiceDate()),
                    req.dueDate(), req.paymentTerm());
            if (!due.date().equals(inv.getDueDate()) || due.term() != inv.getPaymentTerm()) {
                movedTo = due;
                inv.setDueDate(due.date());
                inv.setPaymentTerm(due.term());
            }
        }
        // Only an actual change of POC is checked, so a notes edit still saves when the POC has
        // since been deactivated or the editor may not assign POCs (AC-A5).
        Long previousId = inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId();
        if (req.salesPocUserId() != null && !req.salesPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPoc(userRepository)) {
                throw new BadRequestException("You may not change the Sales POC");
            }
            User poc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES);
            inv.setSalesPoc(poc);
            pocService.notifyAssignee(poc, PocType.SALES,
                    "invoice " + inv.getInvoiceNumber(), "/invoices/" + inv.getId());
        }
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_UPDATED", before,
                InvoiceDtos.InvoiceDto.from(saved),
                currentUser.require().getId(), null, null);
        // Moving a collections deadline gets an entry of its own, old → new, so the History tab
        // shows it without anyone reading two snapshots side by side (AC-A8).
        if (movedTo != null) {
            auditService.record(ENTITY, id, "INVOICE_DUE_DATE_CHANGED",
                    new DueDateSnapshot(before.dueDate(), before.paymentTerm()),
                    new DueDateSnapshot(movedTo.date(), movedTo.term()),
                    currentUser.require().getId(), null, null);
        }
        automationEvents.recordUpdated(AutomationEntityType.INVOICE, saved.getId());
        return saved;
    }

    /** A due date and the terms it came from, for the audit trail. */
    public record DueDateSnapshot(LocalDate dueDate, PaymentTerm paymentTerm) {}

    /** The outcome of working a due date out: the date, and the terms to record it under. */
    private record Due(LocalDate date, PaymentTerm term) {}

    /**
     * Where an invoice's due date comes from (§2.2). Named terms recompute it from the invoice
     * date; a date on its own is an override, recorded as CUSTOM; neither takes the customer's
     * terms, or the system default when they have none.
     */
    private Due due(Customer customer, LocalDate invoiceDay, LocalDate requested, PaymentTerm term) {
        // A date the database cannot hold is a bad field, not a conflict: left to Postgres it came
        // back as 409 "This change conflicts with existing data", which names neither the field
        // nor the rule (INV-4).
        if (requested != null && (requested.isBefore(EARLIEST_DUE_DATE) || requested.isAfter(LATEST_DUE_DATE))) {
            throw fieldError("dueDate",
                    "Enter a date between " + EARLIEST_DUE_DATE + " and " + LATEST_DUE_DATE);
        }
        PaymentTerm resolved;
        LocalDate date;
        if (term != null) {
            resolved = term;
            if (term == PaymentTerm.CUSTOM) {
                if (requested == null) {
                    throw fieldError("dueDate", "Pick a due date for custom terms");
                }
                date = requested;
            } else {
                // Named terms are the rule the date comes from, so a date sent beside them is a
                // request with two minds: it is refused rather than quietly thrown away (§2.2).
                if (requested != null) {
                    throw fieldError("dueDate", "Pick Custom terms to set the due date yourself");
                }
                date = term.due(invoiceDay);
            }
        } else if (requested != null) {
            resolved = PaymentTerm.CUSTOM;
            date = requested;
        } else {
            PaymentTerm own = customer.getPaymentTerm();
            // CUSTOM is refused on a customer, so a row carrying it is one this code never wrote.
            resolved = (own == null || own == PaymentTerm.CUSTOM)
                    ? invoiceProperties.defaultTerm()
                    : own;
            date = resolved.due(invoiceDay);
        }
        if (date.isBefore(invoiceDay)) {
            throw fieldError("dueDate", "The due date cannot be before the invoice date");
        }
        return new Due(date, resolved);
    }

    /** The range a due date may be set to, stated here rather than left to the database (INV-4). */
    static final LocalDate EARLIEST_DUE_DATE = LocalDate.of(1900, 1, 1);
    static final LocalDate LATEST_DUE_DATE = LocalDate.of(9999, 12, 31);

    private static GlobalExceptionHandler.InvalidFieldsException fieldError(String field, String message) {
        return new GlobalExceptionHandler.InvalidFieldsException(Map.of(field, message));
    }

    /**
     * The date the customer's terms would give, for the form to show beside the terms' name the
     * moment a customer is picked (US-A2).
     */
    @Transactional(readOnly = true)
    public InvoiceDtos.DueDatePreview previewDueDate(Long customerId, Instant invoiceDate) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // A customer outside the caller's book answers exactly as reading the customer itself
        // does: this hands over their commercial terms, so it may not confirm they exist either.
        requireCustomerInBook(customerId);
        PaymentTerm own = customer.getPaymentTerm();
        boolean ownTerms = own != null && own != PaymentTerm.CUSTOM;
        PaymentTerm term = ownTerms ? own : invoiceProperties.defaultTerm();
        return new InvoiceDtos.DueDatePreview(
                term.due(InvoiceDates.dayOf(invoiceDate == null ? Instant.now() : invoiceDate)),
                term, term.label(),
                ownTerms ? InvoiceDtos.TermSource.CUSTOMER : InvoiceDtos.TermSource.DEFAULT);
    }

    /** Reassigns just the Sales POC. Used by the inline row action and the bulk action. */
    @Transactional
    public Invoice reassignSalesPoc(Long id, Long userId) {
        return update(id, new InvoiceDtos.UpdateInvoiceRequest(null, userId));
    }

    /**
     * Prices line items against their products. Shared by create and by a dispute that replaces
     * the items, so both refuse the same bad input: no product, a quantity below one, or a
     * negative or sub-cent unit price.
     */
    private List<InvoiceItem> buildLines(Invoice invoice, List<InvoiceDtos.LineInput> inputs) {
        List<InvoiceItem> lines = new ArrayList<>();
        for (InvoiceDtos.LineInput in : inputs) {
            if (in.productId() == null) throw new BadRequestException("Each line needs a product");
            if (in.quantity() < 1) throw new BadRequestException("Quantity must be positive");
            Product p = productRepository.findById(in.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + in.productId()));
            // A product taken out of the catalogue should not appear on a new line (D-47).
            if (!p.isActive()) {
                throw new BadRequestException(p.getName() + " is no longer an active product");
            }
            BigDecimal unitPrice = in.unitPrice() != null ? in.unitPrice() : p.getPrice();
            if (unitPrice.signum() < 0) throw new BadRequestException("Unit price cannot be negative");
            Money.requireCents(unitPrice, "Unit price");
            lines.add(InvoiceItem.builder()
                    .invoice(invoice).product(p)
                    .quantity(in.quantity()).unitPrice(unitPrice)
                    .lineTotal(unitPrice.multiply(BigDecimal.valueOf(in.quantity())))
                    .build());
        }
        return lines;
    }

    private static BigDecimal totalOf(List<InvoiceItem> lines) {
        return lines.stream().map(InvoiceItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static void recomputeStatus(Invoice inv) {
        if (inv.getStatus() == InvoiceStatus.CANCELLED) return;
        BigDecimal balance = inv.getBalance();
        if (balance.signum() <= 0) inv.setStatus(InvoiceStatus.FULLY_PAID);
        else if (inv.getPaidAmount().signum() > 0) inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
        else inv.setStatus(InvoiceStatus.UNPAID);
    }

    @Transactional(readOnly = true)
    public Invoice get(Long id) {
        Invoice inv = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's invoice answers exactly as a missing one does, which is what
        // requireInBook already does for a POC outside their book. Answering 403 here and 404
        // there told a customer login which ids exist across every other customer (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) {
            throw new NotFoundException("Invoice not found");
        }
        requireInBook(id);
        return inv;
    }

    /** No scope check: for disputes, which staff resolve on any customer's records. */
    @Transactional(readOnly = true)
    public Invoice getInternal(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    /** A POC limited to their own book cannot reach another rep's invoice by id either (AC-A6). */
    private void requireInBook(Long id) {
        if (!queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES, id,
                scopeResolver.forInvoices().predicates())) {
            throw new NotFoundException("Invoice not found");
        }
    }

    /** The same gate {@code GET /api/customers/{id}} applies, for the reads that quote a customer. */
    private void requireCustomerInBook(Long id) {
        if (!queryExecutor.inScope(Customer.class, TableSchemas.CUSTOMERS, id,
                scopeResolver.forCustomers().predicates())) {
            throw new NotFoundException("Customer not found");
        }
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
        LocalDate today = InvoiceDates.today();
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
                            Aggregates.countWhen(cb, cb.isNull(root.get("salesPoc"))),
                            Aggregates.sumWhen(cb, TableSchemas.invoiceOverdue(root, cb, today),
                                    cb.diff(total, paid)),
                            Aggregates.countWhen(cb, TableSchemas.invoiceOverdue(root, cb, today)));
                });
        return new InvoiceDtos.InvoiceSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asMoney(row[3]),
                Aggregates.asLong(row[4]), Aggregates.asLong(row[5]), Aggregates.asLong(row[6]),
                Aggregates.asLong(row[7]), Aggregates.asLong(row[8]),
                Aggregates.asMoney(row[9]), Aggregates.asLong(row[10]));
    }

    @Transactional
    public Invoice cancel(Long id) {
        // Under the row lock, so two cancels of the same invoice queue and the second is told the
        // invoice is already cancelled instead of both reporting that they did it (TBL-07).
        Invoice inv = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireInBook(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        // What decides this is money actually taken, not the status: an invoice with nothing on
        // it is born FULLY_PAID, and refusing on the status alone left it uncancellable for ever,
        // under a message saying it had payments it never had (DASH-05).
        if (inv.getPaidAmount().signum() > 0) {
            throw new BadRequestException("Cannot cancel an invoice with payments; refund first");
        }
        Object before = InvoiceDtos.InvoiceDto.from(inv);
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_CANCELLED", before,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.require().getId(), null, null);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        automationEvents.recordUpdated(AutomationEntityType.INVOICE, saved.getId());
        return saved;
    }

    /**
     * Cancel an invoice as part of a dispute resolution. Any amount already paid is refunded to
     * the customer's credit balance, still booked to the payments it came from. Caller
     * (DisputeService) is responsible for audit logging.
     */
    @Transactional
    public Invoice cancelWithRefund(Long id) {
        Invoice inv = getInternal(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        if (inv.getPaidAmount().signum() > 0) {
            creditLedger.refund(inv, inv.getPaidAmount());
            inv.setPaidAmount(BigDecimal.ZERO);
        }
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        automationEvents.recordUpdated(AutomationEntityType.INVOICE, saved.getId());
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

        List<InvoiceItem> lines = buildLines(inv, newItems);
        inv.getItems().clear();
        inv.getItems().addAll(lines);
        BigDecimal total = totalOf(lines);
        inv.setTotal(total);
        if (notes != null) inv.setNotes(notes);

        if (inv.getPaidAmount().compareTo(total) > 0) {
            creditLedger.refund(inv, inv.getPaidAmount().subtract(total));
            inv.setPaidAmount(total);
        }
        recomputeStatus(inv);
        Invoice saved = invoiceRepository.save(inv);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        automationEvents.recordUpdated(AutomationEntityType.INVOICE, saved.getId());
        return saved;
    }
}
