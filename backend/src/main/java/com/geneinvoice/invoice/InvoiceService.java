package com.geneinvoice.invoice;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.Gated;
import com.geneinvoice.approval.MakerChecker;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeCloser;
import com.geneinvoice.approval.PendingChangeScope;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.Records;
import com.geneinvoice.payment.CreditLedger;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.Expression;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private final RecordAccess recordAccess;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final PaymentPromiseService promiseService;
    private final CreditLedger creditLedger;
    private final InvoiceNumbers invoiceNumbers;
    private final InvoiceProperties invoiceProperties;
    private final Records records;
    private final AutomationEvents automationEvents;
    private final MakerChecker makerChecker;
    private final PendingChangeCloser pendingChangeCloser;
    private final PendingChangeScope pendingChangeScope;
    private final Clock clock;

    @Transactional
    public Invoice create(InvoiceDtos.CreateInvoiceRequest req) {
        return createOrHold(req).appliedOrThrow();
    }

    /**
     * Makes the invoice, or, when its total is above the region's INVOICE_CREATE limit, holds it for someone
     * else to approve (B2). A held invoice takes no number: the number is drawn only once it is really made, so a
     * rejected request leaves no gap and an approved one is numbered on the day it is approved.
     */
    @Transactional
    public Outcome<Invoice> createOrHold(InvoiceDtos.CreateInvoiceRequest req) {
        // The customer first (F1): its region is copied onto the invoice, and a move of the customer to
        // another region waits for this save or is waited for, never interleaves with it. Then the number
        // sequence, then the invoice.
        Customer customer = customerRepository.findByIdForUpdate(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        ViewScopeHolder.requireLive();
        // Book-aware: a POC who sees only their own book can neither write to a customer outside it nor read
        // it back through the response (SC-12).
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customer.getId(), Region.idOf(customer.getRegion()),
                "Customer not found");
        currentUser.requireIn(Privileges.INVOICE_MANAGE, Region.idOf(customer.getRegion()));

        User salesPoc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES,
                Region.idOf(customer.getRegion()));

        Instant invoiceDate = req.invoiceDate() == null ? clock.instant() : req.invoiceDate();
        Due due = due(customer, InvoiceDates.dayOf(invoiceDate), req.dueDate(), req.paymentTerm());

        Invoice invoice = Invoice.builder()
                .customer(customer)
                .invoiceDate(invoiceDate)
                .dueDate(due.date())
                .paymentTerm(due.term())
                .notes(req.notes())
                .salesPoc(salesPoc)
                .region(customer.getRegion())
                .build();

        List<InvoiceItem> items = buildLines(invoice, req.items());
        invoice.setItems(items);
        invoice.setTotal(totalOf(items));
        Optional<PendingChange> held = makerChecker.gate(createGate(customer, invoice, req, items));
        if (held.isPresent()) return Outcome.held(held.get());

        invoice.setInvoiceNumber(invoiceNumbers.next());
        Invoice saved = invoiceRepository.save(invoice);
        List<CreditLedger.CreditMove> fromCredit = creditLedger.applyTo(saved);
        recomputeStatus(saved);
        saved = invoiceRepository.save(saved);
        BigDecimal creditUsed = saved.getPaidAmount();
        auditService.record(ENTITY, saved.getId(), "INVOICE_CREATED", null,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.idOrNull(), null,
                creditUsed.signum() > 0 ? "Customer credit applied: " + creditUsed.toPlainString() : null);
        for (CreditLedger.CreditMove m : fromCredit) {
            auditService.record(ENTITY, saved.getId(), "PAYMENT_APPLIED", m.before(), m.after(),
                    currentUser.idOrNull(), null, "Paid from customer credit");
        }
        automationEvents.created(TriggerEntity.INVOICE, saved.getId());
        automationEvents.updated(TriggerEntity.CUSTOMER, customer.getId(), AutomationEvents.OUTSTANDING);

        pocService.notifyAssignee(salesPoc, PocType.SALES,
                "invoice " + saved.getInvoiceNumber(), "/invoices/" + saved.getId(), Region.idOf(saved.getRegion()));
        promiseService.reevaluateForCustomer(customer.getId());
        return Outcome.applied(saved);
    }

    /**
     * A create measures its lines' total. The request is kept with every line's price pinned, so the invoice the
     * approver agrees to is the one that is made, whatever the catalogue says by then.
     */
    private static Gated createGate(Customer customer, Invoice invoice, InvoiceDtos.CreateInvoiceRequest req,
                                    List<InvoiceItem> items) {
        List<InvoiceDtos.LineInput> pinned = items.stream()
                .map(it -> new InvoiceDtos.LineInput(it.getProduct().getId(), it.getQuantity(), it.getUnitPrice()))
                .toList();
        InvoiceDtos.CreateInvoiceRequest payload = new InvoiceDtos.CreateInvoiceRequest(req.customerId(),
                req.invoiceDate(), req.dueDate(), req.paymentTerm(), req.notes(), req.salesPocUserId(), pinned);
        Long pocId = invoice.getSalesPoc() == null ? null : invoice.getSalesPoc().getId();
        return new Gated(ApprovalOperation.INVOICE_CREATE, null, "New invoice for " + customer.getName(), ENTITY,
                null, null, customer.getId(), customer.getName(), Region.idOf(customer.getRegion()), pocId,
                invoice.getTotal(), payload, null,
                new InvoiceChangeApplier.CreateView(invoice.getInvoiceDate(), invoice.getDueDate(),
                        invoice.getPaymentTerm(), invoice.getTotal(), pocId, invoice.getNotes(), pinned),
                "Create invoice for " + customer.getName() + " (" + Money.format(invoice.getTotal()) + ")",
                false);
    }

    @Transactional
    public Invoice update(Long id, InvoiceDtos.UpdateInvoiceRequest req) {
        return updateOrHold(id, req).appliedOrThrow();
    }

    /**
     * Edits the invoice. Moving its due date later on money still owed, above the region's INVOICE_TERMS limit,
     * is held for someone else to approve with the rest of the edit, and the invoice is left as it is (B2).
     * Shortening the terms, and edits that leave them alone, are never held.
     */
    @Transactional
    public Outcome<Invoice> updateOrHold(Long id, InvoiceDtos.UpdateInvoiceRequest req) {
        // Read under the row lock, so simultaneous edits queue instead of all reading the same
        // starting point: the before-snapshot below is then the value the previous edit left, and
        // the History tab reads as a chain rather than as several moves from one date (INV-1).
        Invoice inv = lockForWrite(id);
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
        // Everything is worked out and checked before anything is set: the gate must see the edit whole.
        Due movedTo = null;
        if (req.dueDate() != null || req.paymentTerm() != null) {
            Due due = due(inv.getCustomer(), InvoiceDates.dayOf(inv.getInvoiceDate()),
                    req.dueDate(), req.paymentTerm());
            if (!due.date().equals(inv.getDueDate()) || due.term() != inv.getPaymentTerm()) movedTo = due;
        }
        Long previousId = inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId();
        User newPoc = null;
        if (req.salesPocUserId() != null && !req.salesPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPocIn(Region.idOf(inv.getRegion()))) {
                throw new BadRequestException("You may not change the Sales POC");
            }
            newPoc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES, Region.idOf(inv.getRegion()));
        }
        if (movedTo != null) {
            Optional<PendingChange> held = makerChecker.gate(termsGate(inv, req, movedTo));
            if (held.isPresent()) return Outcome.held(held.get());
        }

        InvoiceDtos.InvoiceDto before = InvoiceDtos.InvoiceDto.from(inv);
        if (req.notes() != null) inv.setNotes(req.notes());
        if (movedTo != null) {
            inv.setDueDate(movedTo.date());
            inv.setPaymentTerm(movedTo.term());
        }
        if (newPoc != null) {
            inv.setSalesPoc(newPoc);
            pocService.notifyAssignee(newPoc, PocType.SALES,
                    "invoice " + inv.getInvoiceNumber(), "/invoices/" + inv.getId(), Region.idOf(inv.getRegion()));
        }
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_UPDATED", before,
                InvoiceDtos.InvoiceDto.from(saved),
                currentUser.require().getId(), null, null);
        if (movedTo != null) {
            auditService.record(ENTITY, id, "INVOICE_DUE_DATE_CHANGED",
                    new DueDateSnapshot(before.dueDate(), before.paymentTerm()),
                    new DueDateSnapshot(movedTo.date(), movedTo.term()),
                    currentUser.require().getId(), null, null);
        }
        automationEvents.updated(TriggerEntity.INVOICE, id, "DETAILS");
        return Outcome.applied(saved);
    }

    /**
     * New terms measure the balance whose due date moves later (nothing when it moves earlier). The approver sees
     * the whole edit; the request is kept without its version, which an approval later could never match.
     */
    private static Gated termsGate(Invoice inv, InvoiceDtos.UpdateInvoiceRequest req, Due movedTo) {
        boolean later = inv.getDueDate() == null || movedTo.date().isAfter(inv.getDueDate());
        BigDecimal amount = later ? inv.getBalance().max(BigDecimal.ZERO) : BigDecimal.ZERO;
        InvoiceChangeApplier.TermsView base = InvoiceChangeApplier.TermsView.of(inv);
        InvoiceChangeApplier.TermsView proposed = new InvoiceChangeApplier.TermsView(movedTo.date(), movedTo.term(),
                req.notes() == null || Objects.equals(InvoiceChangeApplier.norm(req.notes()),
                        InvoiceChangeApplier.norm(base.notes())) ? base.notes() : req.notes(),
                req.salesPocUserId() != null ? req.salesPocUserId() : base.salesPocUserId());
        Customer customer = inv.getCustomer();
        return new Gated(ApprovalOperation.INVOICE_TERMS, inv.getId(), inv.getInvoiceNumber(), ENTITY, inv.getId(),
                null, customer.getId(), customer.getName(), Region.idOf(inv.getRegion()), base.salesPocUserId(),
                amount,
                new InvoiceDtos.UpdateInvoiceRequest(req.notes(), req.salesPocUserId(), req.dueDate(),
                        req.paymentTerm(), null),
                base, proposed,
                "Extend due date of " + inv.getInvoiceNumber() + " from "
                        + (inv.getDueDate() == null ? "none" : inv.getDueDate()) + " to " + movedTo.date()
                        + " on " + Money.format(amount) + " outstanding",
                false);
    }

    public record DueDateSnapshot(LocalDate dueDate, PaymentTerm paymentTerm) {}

    private record Due(LocalDate date, PaymentTerm term) {}

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

    @Transactional(readOnly = true)
    public InvoiceDtos.DueDatePreview previewDueDate(Long customerId, Instant invoiceDate) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customerId, Region.idOf(customer.getRegion()),
                "Customer not found");
        currentUser.requireIn(Privileges.INVOICE_MANAGE, Region.idOf(customer.getRegion()));
        PaymentTerm own = customer.getPaymentTerm();
        boolean ownTerms = own != null && own != PaymentTerm.CUSTOM;
        PaymentTerm term = ownTerms ? own : invoiceProperties.defaultTerm();
        return new InvoiceDtos.DueDatePreview(
                term.due(InvoiceDates.dayOf(invoiceDate == null ? clock.instant() : invoiceDate)),
                term, term.label(),
                ownTerms ? InvoiceDtos.TermSource.CUSTOMER : InvoiceDtos.TermSource.DEFAULT);
    }

    @Transactional
    public Invoice reassignSalesPoc(Long id, Long userId) {
        return update(id, new InvoiceDtos.UpdateInvoiceRequest(null, userId));
    }

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

    /** What these lines would come to, priced and checked as a new invoice's are; nothing is saved. */
    public BigDecimal totalFor(List<InvoiceDtos.LineInput> inputs) {
        return totalOf(buildLines(null, inputs));
    }

    /** The lines with every price filled in, from the catalogue where none was given; nothing is saved. */
    public List<InvoiceDtos.LineInput> priced(List<InvoiceDtos.LineInput> inputs) {
        return buildLines(null, inputs).stream()
                .map(it -> new InvoiceDtos.LineInput(it.getProduct().getId(), it.getQuantity(), it.getUnitPrice()))
                .toList();
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

    /** The invoice, or as of a date the invoice as it was then (not found if it did not exist yet). */
    @Transactional(readOnly = true)
    public Invoice get(Long id) {
        Invoice inv = records.find(Invoice.class, id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's invoice answers exactly as a missing one does, which is what
        // requireVisible already does for a POC outside their book. Answering 403 here and 404
        // there told a customer login which ids exist across every other customer (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) {
            throw new NotFoundException("Invoice not found");
        }
        requireVisible(inv);
        return inv;
    }

    @Transactional(readOnly = true)
    public Invoice getInternal(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    private void requireVisible(Invoice inv) {
        scopeResolver.requireVisible(ScopeResolver.Kind.INVOICE, inv.getId(), Region.idOf(inv.getRegion()),
                "Invoice not found");
    }

    /**
     * An invoice to change, locked customer first (§5.11.5): its customer id is read without a lock, then the
     * customer row is locked, then the invoice, so this path serialises with every other writer on the customer
     * and cannot deadlock with a region move. Its region is read only once both are held. One the caller cannot
     * see is missing (404); one outside the regions they manage invoices in is a 403.
     */
    private Invoice lockForWrite(Long id) {
        Long customerId = invoiceRepository.customerIdOf(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        customerRepository.findByIdForUpdate(customerId);
        Invoice inv = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireVisible(inv);
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.INVOICE_MANAGE, Region.idOf(inv.getRegion()));
        return inv;
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceSummary> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forInvoices();
        boolean poc = scopeResolver.canSeePoc();
        var page = queryExecutor.run(Invoice.class, TableSchemas.INVOICES, query,
                scope.predicates(), List.of("customer", "salesPoc", "region"));
        Set<Long> pending = pendingChangeScope.pendingOn(ENTITY, page.content().stream().map(Invoice::getId).toList());
        return PageResponse.of(page.content().stream()
                        .map(i -> InvoiceDtos.InvoiceSummary.from(i, poc, recordAccess)
                                .withPendingApproval(pending.contains(i.getId())))
                        .toList(),
                query, page.total(), scope);
    }

    /** The ids a bulk action or export may touch: VIEW for an export, MANAGE (and any extras) for a write. */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit, ScopeResolver.Access access, String... alsoRequired) {
        return queryExecutor.ids(Invoice.class, TableSchemas.INVOICES, query,
                scopeResolver.scope(ScopeResolver.Kind.INVOICE, access, ScopeResolver.Reach.SELECTED, alsoRequired)
                        .predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Invoice> allMatching(TableQuery query) {
        return queryExecutor.run(Invoice.class, TableSchemas.INVOICES, query,
                scopeResolver.forInvoices().predicates(), List.of("customer", "salesPoc", "region")).content();
    }

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
        return cancelOrHold(id).appliedOrThrow();
    }

    /**
     * Cancels the invoice, or, above its region's INVOICE_CANCEL limit, holds the cancel for someone else to
     * approve and leaves the invoice exactly as it is (B2).
     */
    @Transactional
    public Outcome<Invoice> cancelOrHold(Long id) {
        // Under the row lock, so two cancels of the same invoice queue and the second is told the
        // invoice is already cancelled instead of both reporting that they did it (TBL-07).
        Invoice inv = lockForWrite(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        // What decides this is money actually taken, not the status: an invoice with nothing on
        // it is born FULLY_PAID, and refusing on the status alone left it uncancellable for ever,
        // under a message saying it had payments it never had (DASH-05).
        if (inv.getPaidAmount().signum() > 0) {
            throw new BadRequestException("Cannot cancel an invoice with payments; refund first");
        }
        Optional<PendingChange> held = makerChecker.gate(cancelGate(inv));
        if (held.isPresent()) return Outcome.held(held.get());
        Object before = InvoiceDtos.InvoiceDto.from(inv);
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_CANCELLED", before,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.require().getId(), null, null);
        automationEvents.invoiceMoney(id, inv.getCustomer().getId(), "STATUS");
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        // Nothing else waiting on a cancelled invoice can still be made.
        pendingChangeCloser.closeForRecord(ENTITY, id, "Invoice cancelled");
        return Outcome.applied(saved);
    }

    /** A cancel measures the invoice's total; the approver is shown it going from its status to CANCELLED. */
    private static Gated cancelGate(Invoice inv) {
        Customer customer = inv.getCustomer();
        return new Gated(ApprovalOperation.INVOICE_CANCEL, inv.getId(), inv.getInvoiceNumber(), ENTITY, inv.getId(),
                null, customer.getId(), customer.getName(), Region.idOf(inv.getRegion()),
                inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId(), inv.getTotal(),
                new InvoiceChangeApplier.CancelPayload(inv.getId()), InvoiceChangeApplier.CancelView.of(inv),
                new InvoiceChangeApplier.CancelView(inv.getTotal(), inv.getPaidAmount(), InvoiceStatus.CANCELLED),
                "Cancel invoice " + inv.getInvoiceNumber() + " for " + customer.getName()
                        + " (" + Money.format(inv.getTotal()) + ")",
                false);
    }

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
        automationEvents.invoiceMoney(id, inv.getCustomer().getId(), "STATUS");
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }

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
        automationEvents.invoiceMoney(id, inv.getCustomer().getId(), "ITEMS");
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }
}
