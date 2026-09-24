package com.geneinvoice.invoice;

import com.geneinvoice.automation.Change;
import com.geneinvoice.automation.ChangeFeed;
import com.geneinvoice.automation.SubjectType;
import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalGate;
import com.geneinvoice.approval.ApprovalSchemas;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.payment.CreditLedger;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionPlacements;
import com.geneinvoice.region.RegionScope;
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
import java.util.Objects;
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
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final PaymentPromiseService promiseService;
    private final CreditLedger creditLedger;
    private final InvoiceNumbers invoiceNumbers;
    private final UserRepository userRepository;
    private final InvoiceProperties invoiceProperties;
    // The region chips this list says it is narrowed by; empty for an unregioned table,
    // for a wildcard holder and for a customer login, so it is passed unconditionally (B1).
    private final RegionScope regionScope;
    // The write-side gate. Reads are emptied by the query predicate and read as nonexistent;
    // a write against a branch the caller cannot manage is refused out loud (B1).
    private final RegionAccess regionAccess;
    // Above this branch's limit the save does not happen at all: the gate throws, this whole
    // transaction rolls back and the request is answered 202 with the change that is waiting (B2).
    private final ApprovalGate approvalGate;
    // Which invoices have a change waiting on them. The repository and not ApprovalService,
    // because a page of rows costs ONE query for the whole page rather than one per row — and
    // because InvoiceService is what ApprovalService replays, so the other direction would be a
    // Spring cycle (B2).
    private final PendingChangeRepository pendingChangeRepository;
    // Both calls below are DEFENSIVE. Every ordinary invoice write audits against INVOICE and is
    // published by the audit hook; the two dispute hatches are audited only by their one caller,
    // so they say so themselves and coalescing collapses the pair when it does (A1).
    private final ChangeFeed changeFeed;
    // The lines an invoice HAD on the date asked. The item mirror is the one mirror with no
    // TableSchema — an invoice line is never a list in its own right — so it is read by id and
    // interval rather than through the executor (B3).
    private final InvoiceItemHistoryRepository invoiceItemHistoryRepository;
    // No mirror carries region_id (blueprint conflict 1 struck it), so an as-of row's branch comes
    // from R7's customer_region_history, batched once per page (B3, B1).
    private final RegionPlacements regionPlacements;
    // Whether this answer may call itself exact: a row the reconciler REPAIRED was dated from when
    // the sweep noticed rather than from when the change happened. One indexed exists per
    // response, and nothing at all on the live path (B3).
    private final HistoryDrift historyDrift;

    @Transactional
    public Invoice create(InvoiceDtos.CreateInvoiceRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // Raising an invoice against an account is a write in that account's branch, and this was
        // the create path with no scope check of any kind: anybody holding INVOICE_MANAGE could
        // bill any customer in the company. .getId() on the lazy Region proxy initialises nothing
        // (B1).
        regionAccess.requireManage(customer.getRegion().getId());

        // The account's own branch: a Sales POC who cannot work there would hold a record that
        // shows them nothing, because the book axis and the region axis are ANDed (B1).
        User salesPoc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES,
                customer.getRegion().getId());

        Instant invoiceDate = req.invoiceDate() == null ? Instant.now() : req.invoiceDate();
        Due due = due(customer, InvoiceDates.dayOf(invoiceDate), req.dueDate(), req.paymentTerm());

        Invoice invoice = Invoice.builder()
                .customer(customer)
                .invoiceDate(invoiceDate)
                .dueDate(due.date())
                .paymentTerm(due.term())
                .notes(req.notes())
                .invoiceNumber(invoiceNumbers.next())
                .salesPoc(salesPoc)
                .build();

        List<InvoiceItem> items = buildLines(invoice, req.items());
        // Here and not earlier, because this is the first point at which what the invoice is
        // WORTH is known: the request carries lines, not a total, and a line with no unit price
        // is priced off the catalogue inside buildLines (B2).
        //
        // invoiceNumbers.next() has already run, up in the builder, and is deliberately left
        // there: it is @Transactional(propagation = MANDATORY) (InvoiceNumbers.java:30) and joins
        // THIS transaction, so the rollback below undoes the sequence increment and a held create
        // consumes no invoice number. The number is therefore allocated at APPROVAL time — a
        // change submitted at 23:58 and approved at 00:02 gets the next day's number (B2).
        approvalGate.check(ApprovalGate.Proposal.creating(
                PendingAction.INVOICE_CREATE, customer.getId(), totalOf(items), priced(req, items),
                "Raise an invoice of " + Money.format(totalOf(items)) + " for " + customer.getName()));
        invoice.setItems(items);
        invoice.setTotal(totalOf(items));
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

        pocService.notifyAssignee(salesPoc, PocType.SALES,
                "invoice " + saved.getInvoiceNumber(), "/invoices/" + saved.getId());
        promiseService.reevaluateForCustomer(customer.getId());
        return saved;
    }

    @Transactional
    public Invoice update(Long id, InvoiceDtos.UpdateInvoiceRequest req) {
        // Read under the row lock, so simultaneous edits queue instead of all reading the same
        // starting point: the before-snapshot below is then the value the previous edit left, and
        // the History tab reads as a chain rather than as several moves from one date (INV-1).
        Invoice inv = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireInBook(id);
        // Reaching the row is a read and is already answered by requireInBook with 404; CHANGING
        // it needs MANAGE where the account lives, so a view-only grant reads this invoice and is
        // refused when it tries to edit it (B1, AUTH-08, D-46).
        regionAccess.requireManage(inv.getCustomer().getRegion().getId());
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
        // not gated (B2): notes, the due date and the Sales POC move no money, and
        // UpdateInvoiceRequest carries no monetary field. An invoice's total only ever changes
        // through create and replaceItems, both of which are gated. A due-date move changes
        // overdue REPORTING, not money.
        InvoiceDtos.InvoiceDto before = InvoiceDtos.InvoiceDto.from(inv);

        if (req.notes() != null) inv.setNotes(req.notes());
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
        Long previousId = inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId();
        if (req.salesPocUserId() != null && !req.salesPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPoc(userRepository)) {
                throw new BadRequestException("You may not change the Sales POC");
            }
            User poc = pocService.requireAssignable(req.salesPocUserId(), PocType.SALES,
                    inv.getCustomer().getRegion().getId());
            inv.setSalesPoc(poc);
            pocService.notifyAssignee(poc, PocType.SALES,
                    "invoice " + inv.getInvoiceNumber(), "/invoices/" + inv.getId());
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
        return saved;
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
        requireCustomerInBook(customerId);
        PaymentTerm own = customer.getPaymentTerm();
        boolean ownTerms = own != null && own != PaymentTerm.CUSTOM;
        PaymentTerm term = ownTerms ? own : invoiceProperties.defaultTerm();
        return new InvoiceDtos.DueDatePreview(
                term.due(InvoiceDates.dayOf(invoiceDate == null ? Instant.now() : invoiceDate)),
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

    /**
     * THE PARKED PAYLOAD IS PRICED, AND THAT IS THE INVARIANT: the amount the approver reads is
     * the amount that lands. buildLines falls back to the live catalogue price when a request line
     * carries no unitPrice, and the shipped form never sends one — so replaying the REQUEST at
     * approval time re-prices the invoice from products.price as it is then, while the exposure,
     * the summary and the audit row all still say what it cost when it was raised. Product.price
     * is deliberately ungated (B2-maker-checker.md:126) on the premise that a catalogue price is
     * COPIED onto the line at create; for a held create there is no line yet to have copied it, so
     * the copy is made HERE instead, into the payload. A change that waits a week is then applied
     * at the price it was approved at, and a product withdrawn in the meantime is still refused by
     * buildLines on replay (B2).
     */
    private static List<InvoiceDtos.LineInput> frozen(List<InvoiceItem> lines) {
        return lines.stream()
                .map(l -> new InvoiceDtos.LineInput(l.getProduct().getId(), l.getQuantity(),
                        l.getUnitPrice()))
                .toList();
    }

    /** The same request with its lines priced, so a held INVOICE_CREATE replays at this price (B2). */
    private static InvoiceDtos.CreateInvoiceRequest priced(InvoiceDtos.CreateInvoiceRequest req,
                                                           List<InvoiceItem> items) {
        return new InvoiceDtos.CreateInvoiceRequest(req.customerId(), req.invoiceDate(),
                req.dueDate(), req.paymentTerm(), req.notes(), req.salesPocUserId(), frozen(items));
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

    /**
     * ONE INVOICE, LIVE OR AS OF A DATE — and 404, never 403, when it did not exist then (B3).
     *
     * <p>THE DISCONTINUITY THIS CLOSES is the reason the single record is in the slice at all: an
     * honest as-of list that dropped the reader onto a detail page showing TODAY'S values would
     * undo the whole feature one click after delivering it. So the detail GET roots on the same
     * mirror, through the same executor, with the same scope list — {@code id:eq:} and nothing
     * else added.
     *
     * <p>AND IT IS 404. An invoice raised last March is not a record this caller may not see; it
     * is a record that did not exist on the date they asked about. Both answer "Invoice not
     * found", which is AUTH-08's rule that a record you merely REACHED and cannot have is
     * indistinguishable from one that is not there — and here it is also simply true.
     *
     * <p>The book, the region axis and a customer login's own-account pin all come from the scope
     * list and the executor's mandatory region predicate, exactly as they do for the list, so
     * there is no second copy of {@code get}'s customer check here to drift from it.
     */
    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceDto detail(Long id, boolean includePoc) {
        // Both arms, because a customer login reads the live invoice and the as-of one by the
        // same route and the branch is no more theirs on one than on the other (B1, AUTH-08).
        boolean region = scopeResolver.canSeeRegion();
        if (!AsOfContext.isActive()) {
            InvoiceDtos.InvoiceDto live =
                    InvoiceDtos.InvoiceDto.from(get(id), includePoc, approvalPending(id));
            return region ? live : live.withoutRegion();
        }
        AsOfSource<InvoiceView> source = invoiceSource();
        List<? extends InvoiceView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()) {
            throw new NotFoundException("Invoice not found");
        }
        placeRegions(rows);
        markDrift();
        InvoiceHistory version = (InvoiceHistory) rows.get(0);
        // The LINES it had then, in force at the same instant the row was chosen at — never
        // today's lines on a past invoice, which is the same leak the flat customer_id closes for
        // the account (B3).
        List<InvoiceItemHistory> lines =
                invoiceItemHistoryRepository.inForce(version.getId(), AsOfContext.instant());
        InvoiceDtos.InvoiceDto past =
                InvoiceDtos.InvoiceDto.from(version, lines, includePoc, approvalPending(id));
        return region ? past : past.withoutRegion();
    }

    /**
     * A DELIBERATE ESCAPE HATCH, named so grep finds it. It reads an invoice past the book and
     * past the region predicate, and it exists for one caller: DisputeService applying a change a
     * customer raised and an administrator approved. The region check for that act is on the
     * APPROVAL, in DisputeService.approve, where the person deciding it is known — the customer
     * who raised the dispute holds no grants at all and would be refused here (B1).
     */
    @Transactional(readOnly = true)
    public Invoice getInternalForDisputeApplication(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    /**
     * WHERE AN INVOICE LIST READS FROM, AND IT IS THE WHOLE OF THIS UNIT'S "ROUTING" (B3).
     *
     * <p>Live it is the invoices table, its live schema and the POC book. Under {@code ?asOf} it
     * is the interval mirror, its as-of twin, and the SAME book — because the book predicate names
     * {@code salesPocUserId}, a flat Long both roots carry, which is what B3-BOOKROOT was for.
     * Everything else in this class reads it and branches on nothing.
     *
     * <p>{@code AsOf.at(T)} LEADS THE SCOPE LIST AND IS THE LINE THAT MAKES THE LIST COUNT
     * RECORDS. The twin carries the interval clause only INSIDE its correlated subqueries — the
     * region ledger, the outstanding approvals, the seats — and nothing filters the ROOT. Without
     * this predicate a page over invoice_history would return every VERSION of every invoice, and
     * {@code totalElements} would count edits.
     *
     * <p>IT ADDS NO REGION PREDICATE, deliberately. Blueprint conflict 1 struck B3's own
     * {@code RegionScope.forAsOf}: the region axis is injected by
     * {@code TableQueryExecutor.predicates()} itself, once, for every root — and it is already
     * correct for a mirror root because B3-CONTEXT pointed {@code RegionScope.effectiveAsOf} at
     * {@code AsOfContext.date()}. A second injection point here would give invoices a region rule
     * of their own that nothing else in the application shares (B1, B3).
     *
     * <p>{@code customer} drops out of the fetch list because a mirror has no {@code Customer} to
     * walk — the account's name as of then is a column on the row — while {@code salesPoc} stays,
     * because the person is NOT mirrored and renders as they are today (clause a.3).
     */
    public AsOfSource<InvoiceView> invoiceSource() {
        ScopeResolver.Scope book = scopeResolver.forInvoices();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(Invoice.class, TableSchemas.INVOICES, book.predicates(),
                    book.lockedFilters(), List.of("customer", "salesPoc"));
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(InvoiceHistory.class, HistorySchemas.INVOICES,
                List.copyOf(scope), List.copyOf(locked), List.of("salesPoc"));
    }

    /**
     * The branch an as-of row cannot answer for itself. No mirror carries {@code region_id}, so
     * the two {@code @Transient} slots on the row are filled from R7's placement ledger before the
     * DTO factory reads them — one query for the page, and not one statement on the live path,
     * where the row answers through its own account (B3, B1).
     */
    private void placeRegions(List<? extends InvoiceView> rows) {
        if (!AsOfContext.isActive()) return;
        List<InvoiceHistory> mirrors = rows.stream()
                .filter(InvoiceHistory.class::isInstance).map(InvoiceHistory.class::cast).toList();
        if (mirrors.isEmpty()) return;
        Map<Long, RegionPlacements.Placement> placements = regionPlacements.at(
                mirrors.stream().map(InvoiceHistory::getCustomerId).filter(Objects::nonNull).toList(),
                AsOfContext.date());
        for (InvoiceHistory row : mirrors) {
            RegionPlacements.Placement placed = placements.get(row.getCustomerId());
            // A setter on a @Transient field does NOT dirty the managed row, which is why these
            // two are transient: writing a persisted field here would be flushed and would rewrite
            // history in order to answer a question about it (B3).
            row.setRegionId(placed == null ? null : placed.regionId());
            row.setRegionName(placed == null ? null : placed.regionName());
        }
    }

    /**
     * Downgrade this answer if the invoice mirror holds a row the reconciler repaired rather than
     * watched happen. The guard is on {@code isActive()} and not inside markIfDrifted, because
     * {@code AsOfContext.instant()} THROWS when nothing is open — evaluating the argument is
     * already too late (B3).
     */
    private void markDrift() {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(InvoiceHistory.class, AsOfContext.instant());
    }

    private void requireInBook(Long id) {
        AsOfSource<InvoiceView> source = invoiceSource();
        if (!queryExecutor.inScope(source.type(), source.schema(), id, source.scope())) {
            throw new NotFoundException("Invoice not found");
        }
    }

    /**
     * Is there a change waiting on this one invoice? The sentinel column answers it in one
     * indexed lookup, and a decided change releases its key, so only a change still waiting can
     * be found here (B2).
     */
    @Transactional(readOnly = true)
    public boolean approvalPending(Long id) {
        // UNDER AN AS-OF DATE THE SENTINEL CANNOT ANSWER IT. pending_key is RELEASED the moment a
        // change is decided, so "is one waiting now" is the only question it can be asked; "was
        // one waiting THEN" is a question for the decision log, which pending_changes already is
        // — requestedAt opens the interval and decidedAt closes it. openTargetIds makes that
        // switch in one place for the whole application, so this reads it rather than spelling
        // AsOf.outstandingAt a second time (B2, B3).
        if (AsOfContext.isActive()) {
            return pendingChangeRepository.openTargetIds(PendingTargetType.INVOICE, List.of(id))
                    .contains(id);
        }
        return pendingChangeRepository.existsByPendingKey(
                PendingChange.keyOf(PendingTargetType.INVOICE, id));
    }

    private void requireCustomerInBook(Long id) {
        if (!queryExecutor.inScope(Customer.class, TableSchemas.CUSTOMERS, id,
                scopeResolver.forCustomers().predicates())) {
            throw new NotFoundException("Customer not found");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceDtos.InvoiceSummary> page(TableQuery query) {
        AsOfSource<InvoiceView> source = invoiceSource();
        boolean poc = scopeResolver.canSeePoc();
        boolean region = scopeResolver.canSeeRegion();
        TableQueryExecutor.Page<? extends InvoiceView> page = queryExecutor.run(
                source.type(), source.schema(), query, source.scope(), source.fetch());
        placeRegions(page.content());
        markDrift();
        // One query for the whole page, and none at all for an empty one: a page of 25 invoices
        // costs exactly one extra statement however many of them are held (B2).
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.INVOICE,
                page.content().stream().map(InvoiceView::getId).toList());
        return PageResponse.of(
                page.content().stream()
                        .map(i -> InvoiceDtos.InvoiceSummary.from(i, poc, held.contains(i.getId())))
                        // WHICH BRANCH is internal: a customer login gets B1's two slots
                        // empty, the same two TableSchema.visibleTo drops from their
                        // column list, so the payload and the schema agree (B1, AUTH-08).
                        .map(row -> region ? row : row.withoutRegion())
                        .toList(),
                query, page.total(), source.locked(), regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<InvoiceView> source = invoiceSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /**
     * The rows behind an export. {@code ? extends InvoiceView} and not {@code Invoice}, because
     * under {@code ?asOf} these are mirror rows — and the caller renders them through the view,
     * which is the whole reason the interface exists (B3).
     */
    @Transactional(readOnly = true)
    public List<? extends InvoiceView> allMatching(TableQuery query) {
        AsOfSource<InvoiceView> source = invoiceSource();
        List<? extends InvoiceView> rows = queryExecutor.run(
                source.type(), source.schema(), query, source.scope(), source.fetch()).content();
        placeRegions(rows);
        markDrift();
        return rows;
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceSummaryTiles tiles(TableQuery query) {
        AsOfSource<InvoiceView> source = invoiceSource();
        // As-of aware since B3-CONTEXT, with no edit here: under an open context this IS the date
        // asked for, so TableSchemas.invoiceOverdue below ages the tiles against that date rather
        // than against today. The ~34 selection lambdas are untouched — every one of them names an
        // attribute the mirror spells the same way and types the same way (B3).
        LocalDate today = InvoiceDates.today();
        markDrift();
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(), query,
                source.scope(), (root, q, cb) -> {
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
                            Aggregates.countWhen(cb, TableSchemas.invoiceOverdue(root, cb, today)),
                            // Inside the SAME aggregate, so the tile costs no extra round trip.
                            // A correlated EXISTS in the case expression of an aggregate select
                            // is the one genuinely unusual construct here, and it is what lets
                            // the tile and the approvalPending filter chip agree by construction
                            // rather than by two people remembering the same rule (B2).
                            Aggregates.countWhen(cb, ApprovalSchemas.existsOpenPending(
                                    root, q, cb, PendingTargetType.INVOICE)));
                });
        return new InvoiceDtos.InvoiceSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asMoney(row[3]),
                Aggregates.asLong(row[4]), Aggregates.asLong(row[5]), Aggregates.asLong(row[6]),
                Aggregates.asLong(row[7]), Aggregates.asLong(row[8]),
                Aggregates.asMoney(row[9]), Aggregates.asLong(row[10]),
                Aggregates.asLong(row[11]));
    }

    @Transactional
    public Invoice cancel(Long id) {
        // Under the row lock, so two cancels of the same invoice queue and the second is told the
        // invoice is already cancelled instead of both reporting that they did it (TBL-07).
        Invoice inv = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        requireInBook(id);
        // Cancelling is a write in the account's branch, like every other change to it (B1).
        regionAccess.requireManage(inv.getCustomer().getRegion().getId());
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        // What decides this is money actually taken, not the status: an invoice with nothing on
        // it is born FULLY_PAID, and refusing on the status alone left it uncancellable for ever,
        // under a message saying it had payments it never had (DASH-05).
        if (inv.getPaidAmount().signum() > 0) {
            throw new BadRequestException("Cannot cancel an invoice with payments; refund first");
        }
        // The request carries no amount: what leaves the book is the balance still outstanding on
        // the record, read off the record (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.INVOICE_CANCEL, id, inv.getCustomer().getId(), inv.getBalance(),
                new ApprovalDtos.NoPayload(), InvoiceDtos.InvoiceDto.from(inv), inv.getVersion(),
                "Cancel invoice " + inv.getInvoiceNumber() + " of " + Money.format(inv.getTotal())
                        + " for " + inv.getCustomer().getName()));
        Object before = InvoiceDtos.InvoiceDto.from(inv);
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        auditService.record(ENTITY, id, "INVOICE_CANCELLED", before,
                InvoiceDtos.InvoiceDto.from(saved), currentUser.require().getId(), null, null);
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }

    /**
     * A DELIBERATE ESCAPE HATCH, named so grep finds it: cancelling and refunding past the book
     * and past the region predicate, for DisputeService applying an approved dispute. The region
     * check is on the approval and not here (B1).
     */
    @Transactional
    public Invoice cancelWithRefundForDisputeApplication(Long id) {
        Invoice inv = getInternalForDisputeApplication(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Invoice already cancelled");
        }
        // max(before, after): cancelling with a refund both takes the whole invoice off the book
        // and pushes everything paid on it back into the customer's credit, and the larger of the
        // two is what somebody is being asked to agree to. Suppressed by ApprovalContext.applying
        // when a dispute approval has already been measured whole (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.INVOICE_CANCEL_WITH_REFUND, id, inv.getCustomer().getId(),
                inv.getTotal().max(inv.getPaidAmount()), new ApprovalDtos.NoPayload(),
                InvoiceDtos.InvoiceDto.from(inv), inv.getVersion(),
                "Cancel and refund invoice " + inv.getInvoiceNumber() + " of "
                        + Money.format(inv.getTotal()) + " for " + inv.getCustomer().getName()));
        if (inv.getPaidAmount().signum() > 0) {
            creditLedger.refund(inv, inv.getPaidAmount());
            inv.setPaidAmount(BigDecimal.ZERO);
        }
        inv.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        // Defensive: this hatch is audited today only by DisputeService.approve, its one caller,
        // and coalescing makes the call free while that stays true (A1).
        changeFeed.changed(SubjectType.INVOICE, saved.getId(), Change.UPDATED);           // (A1)
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }

    /**
     * A DELIBERATE ESCAPE HATCH, named so grep finds it: replacing an invoice's lines past the
     * book and past the region predicate, for DisputeService applying an approved dispute. The
     * region check is on the approval and not here (B1).
     */
    @Transactional
    public Invoice replaceItemsForDisputeApplication(Long id, List<InvoiceDtos.LineInput> newItems,
                                                     String notes) {
        if (newItems == null || newItems.isEmpty()) {
            throw new BadRequestException("Items must not be empty");
        }
        Invoice inv = getInternalForDisputeApplication(id);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BadRequestException("Cannot edit a cancelled invoice");
        }

        List<InvoiceItem> lines = buildLines(inv, newItems);
        // max(before, after) again: replacing the lines clears and rebuilds every one of them and
        // can refund the difference to credit (:499-502), so a replacement is a full reversal
        // followed by a full re-application and is scored at its larger side (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.INVOICE_REPLACE_ITEMS, id, inv.getCustomer().getId(),
                inv.getTotal().max(totalOf(lines)),
                new ApprovalDtos.ItemsChange(frozen(lines), notes),
                InvoiceDtos.InvoiceDto.from(inv), inv.getVersion(),
                "Replace the lines of invoice " + inv.getInvoiceNumber() + ": "
                        + Money.format(inv.getTotal()) + " becomes " + Money.format(totalOf(lines))));
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
        // Defensive, for the same reason as the hatch above: replacing an invoice's lines is a
        // change to the invoice whoever else ever calls it (A1).
        changeFeed.changed(SubjectType.INVOICE, saved.getId(), Change.UPDATED);           // (A1)
        promiseService.reevaluateForCustomer(inv.getCustomer().getId());
        return saved;
    }
}
