package com.geneinvoice.payment;

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
import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.customer.CustomerHistoryRepository;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceHistoryRepository;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionPlacements;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Expression;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String ENTITY = "PAYMENT";

    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    // The invoice mirror, read for ONE thing: the invoices a past payment was applied to,
    // as those invoices stood then. payment_allocation_history carries the allocated amount
    // but not the invoice's number, total or status (B3).
    private final InvoiceHistoryRepository invoiceHistoryRepository;
    // Which invoices a payment covered THEN, with the amounts it covered them for (B3).
    private final PaymentAllocationHistoryRepository paymentAllocationHistoryRepository;
    // The account's credit balance THEN. A payment row carries it, and on a past page it
    // has to be the balance the account held on that date (B3).
    private final CustomerHistoryRepository customerHistoryRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final PaymentPromiseService promiseService;
    private final UserRepository userRepository;
    // The region chips this list says it is narrowed by; empty for an unregioned table,
    // for a wildcard holder and for a customer login, so it is passed unconditionally (B1).
    private final RegionScope regionScope;
    // No mirror carries region_id, so an as-of row's branch comes from R7's placement
    // ledger. One query for the page, and not one statement on the live path (B3, B1).
    private final RegionPlacements regionPlacements;
    // Says so when a row in this answer was repaired rather than watched happen (B3).
    private final HistoryDrift historyDrift;
    // The write-side gate. Reads are emptied by the query predicate and read as nonexistent;
    // a write against a branch the caller cannot manage is refused out loud (B1).
    private final RegionAccess regionAccess;
    // Above this branch's limit the save does not happen at all: the gate throws, this whole
    // transaction rolls back and the request is answered 202 with the change that is waiting (B2).
    private final ApprovalGate approvalGate;
    // Which payments have a change waiting on them. The repository and not ApprovalService: one
    // query for a whole page, and the other direction would be a Spring cycle because
    // ApprovalService replays this service (B2).
    private final PendingChangeRepository pendingChangeRepository;

    /**
     * Two of the four calls below are LOAD-BEARING and two are defensive. applyTo and
     * reverseAllocations move {@code customer.credit_balance} and audit nothing against CUSTOMER,
     * so they are the only source for that change; voidPayment and updateAmount are audited today
     * only through DisputeService.approve, and their calls are free because per-transaction
     * coalescing collapses them into the row the audit hook publishes anyway (A1).
     */
    private final ChangeFeed changeFeed;

    @Transactional
    public Payment record(PaymentDtos.CreatePaymentRequest req) {
        // The first lock of the money path, before anything of this customer's is read: two
        // cashiers recording a payment for one customer at the same instant queue here instead of
        // both reading the same paid amounts and credit balance and each writing their own
        // figures over the other's, which loses one payment's money outright (PPD-01).
        Customer customer = lockCustomer(req.customerId());
        // Taking money for an account is a write in that account's branch, and this was the second
        // create path with no scope check at all: anybody holding PAYMENT_MANAGE could record a
        // payment against any customer in the company. Read off the row already locked above, so
        // it costs nothing (B1).
        regionAccess.requireManage(customer.getRegion().getId());

        // Money above this region's limit is not taken on the strength of one person's save: the
        // gate throws, this whole transaction rolls back, and the request is answered 202 with the
        // change that is now waiting for a second pair of eyes (B2). Placed straight after the
        // lock, so the region is read off a customer row nobody else can move underneath it, and
        // after the region check, so somebody who may not write here is still told so.
        approvalGate.check(ApprovalGate.Proposal.creating(
                PendingAction.PAYMENT_RECORD, customer.getId(), req.amount(), req,
                "Record a payment of " + Money.format(req.amount()) + " for " + customer.getName()));

        // The account's own branch, read off the row already locked above (B1).
        User collectionPoc = pocService.requireAssignable(req.collectionPocUserId(),
                PocType.COLLECTION, customer.getRegion().getId());

        List<Long> chosen = req.invoiceIds() == null ? List.of() : req.invoiceIds();
        Set<Long> payFirst = promiseService.invoicesToPayFirst(req.promiseIds(), customer.getId(),
                Set.copyOf(chosen));
        List<Invoice> targets;
        if (!chosen.isEmpty()) {
            List<Invoice> loaded = new ArrayList<>(invoiceRepository.findAllByIdForUpdate(chosen));
            // An id that matches no invoice would otherwise be ignored and the money would quietly
            // become customer credit (D-48).
            List<Long> missing = chosen.stream()
                    .filter(id -> loaded.stream().noneMatch(inv -> inv.getId().equals(id)))
                    .toList();
            if (!missing.isEmpty()) {
                throw new NotFoundException("Invoice not found: " + missing);
            }
            targets = loaded;
            for (Invoice inv : targets) {
                if (!inv.getCustomer().getId().equals(customer.getId())) {
                    throw new BadRequestException(
                            "Invoice " + inv.getInvoiceNumber() + " does not belong to this customer");
                }
            }
        } else {
            targets = new ArrayList<>(invoiceRepository.findByCustomerIdForUpdate(customer.getId()));
        }
        targets.sort(Comparator.comparing((Invoice i) -> !payFirst.contains(i.getId())).thenComparing(OLDEST_FIRST));

        Payment payment = Payment.builder()
                .customer(customer)
                .amount(req.amount())
                .method(req.method())
                .notes(req.notes())
                .collectionPoc(collectionPoc)
                .status(PaymentStatus.ACTIVE)
                .build();

        List<Movement> applied = applyTo(payment, targets, req.amount());
        Payment saved = paymentRepository.save(payment);
        auditService.record(ENTITY, saved.getId(), "PAYMENT_RECORDED", null,
                PaymentDtos.PaymentDto.from(saved), currentUser.idOrNull(), null, req.notes());
        auditMovements(saved, applied, "PAYMENT_APPLIED");

        pocService.notifyAssignee(collectionPoc, PocType.COLLECTION,
                "payment #" + saved.getId(), "/payments/" + saved.getId());
        promiseService.reevaluateForCustomer(customer.getId());
        return saved;
    }

    @Transactional
    public Payment update(Long id, PaymentDtos.UpdatePaymentRequest req) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        requireInBook(id);
        // Reaching the row is a read and is already answered by requireInBook with 404; CHANGING
        // it needs MANAGE where the account lives (B1, AUTH-08, D-46).
        regionAccess.requireManage(p.getCustomer().getRegion().getId());
        // not gated (B2): UpdatePaymentRequest is {notes, collectionPocUserId} and carries no
        // monetary field. A payment's amount only ever moves through record, updateAmount and
        // voidPayment, all three of which are gated.
        Object before = PaymentDtos.PaymentDto.from(p);

        if (req.notes() != null) p.setNotes(req.notes());
        Long previousId = p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId();
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPoc(userRepository)) {
                throw new BadRequestException("You may not change the Collection POC");
            }
            User poc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION,
                    p.getCustomer().getRegion().getId());
            p.setCollectionPoc(poc);
            pocService.notifyAssignee(poc, PocType.COLLECTION,
                    "payment #" + p.getId(), "/payments/" + p.getId());
        }
        Payment saved = paymentRepository.save(p);
        auditService.record(ENTITY, id, "PAYMENT_UPDATED", before, PaymentDtos.PaymentDto.from(saved),
                currentUser.require().getId(), null, null);
        return saved;
    }

    @Transactional
    public Payment reassignCollectionPoc(Long id, Long userId) {
        return update(id, new PaymentDtos.UpdatePaymentRequest(null, userId));
    }

    public record InvoicePaymentAudit(Long paymentId, BigDecimal amount, BigDecimal paidAmount,
                                      BigDecimal balance, InvoiceStatus status) {}

    private record Movement(Invoice invoice, BigDecimal amount,
                            BigDecimal paidBefore, InvoiceStatus statusBefore) {}

    private void auditMovements(Payment payment, List<Movement> moves, String action) {
        Long actor = currentUser.idOrNull();
        for (Movement m : moves) {
            Invoice inv = m.invoice();
            auditService.record(InvoiceService.ENTITY, inv.getId(), action,
                    new InvoicePaymentAudit(payment.getId(), m.amount(), m.paidBefore(),
                            inv.getTotal().subtract(m.paidBefore()), m.statusBefore()),
                    new InvoicePaymentAudit(payment.getId(), m.amount(), inv.getPaidAmount(),
                            inv.getBalance(), inv.getStatus()),
                    actor, null, null);
        }
    }

    private static final Comparator<Invoice> OLDEST_FIRST = Comparator.comparing(Invoice::getInvoiceDate);

    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    private void lockInvoices(List<Invoice> invoices) {
        List<Long> ids = invoices.stream().map(Invoice::getId).filter(java.util.Objects::nonNull).toList();
        if (!ids.isEmpty()) invoiceRepository.findAllByIdForUpdate(ids);
    }

    private List<Movement> applyTo(Payment payment, List<Invoice> targets, BigDecimal amount) {
        // Every row this is about to change is held under the write lock first, in the one order
        // the whole money path uses: the customer, then the invoices by ascending id (PPD-01).
        // Re-taking a lock this transaction already holds costs a query and changes nothing, so
        // the method is safe to call from anywhere rather than only from a caller that knows.
        Customer customer = lockCustomer(payment.getCustomer().getId());
        lockInvoices(targets);
        List<Invoice> outstanding = new ArrayList<>(targets.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.FULLY_PAID && i.getStatus() != InvoiceStatus.CANCELLED)
                .toList());

        List<Movement> moves = new ArrayList<>();
        BigDecimal remaining = amount;
        for (Invoice inv : outstanding) {
            if (remaining.signum() <= 0) break;
            BigDecimal balance = inv.getBalance();
            if (balance.signum() <= 0) continue;
            BigDecimal toApply = balance.min(remaining);
            moves.add(new Movement(inv, toApply, inv.getPaidAmount(), inv.getStatus()));
            inv.setPaidAmount(inv.getPaidAmount().add(toApply));
            InvoiceService.recomputeStatus(inv);
            invoiceRepository.save(inv);
            payment.getAllocations().add(PaymentAllocation.builder()
                    .payment(payment).invoice(inv).amount(toApply).build());
            remaining = remaining.subtract(toApply);
        }
        payment.setCreditApplied(remaining);
        if (remaining.signum() > 0) {
            customer.setCreditBalance(customer.getCreditBalance().add(remaining));
            customerRepository.save(customer);
            // LOAD-BEARING: an overpayment becomes customer credit and nothing here writes an
            // audit row against CUSTOMER, so this is the only place the change is published (A1).
            changeFeed.changed(SubjectType.CUSTOMER, customer.getId(), Change.UPDATED);   // (A1)
        }
        return moves;
    }

    @Transactional
    public Payment voidPayment(Long paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (p.getStatus() == PaymentStatus.VOIDED) {
            throw new BadRequestException("Payment already voided");
        }
        lockCustomer(p.getCustomer().getId());
        // Taking a payment back off the book moves the whole of it, and the request that asks for
        // it carries no amount at all: the exposure is read off the RECORD, which is the case a
        // threshold on the request body misses entirely (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.PAYMENT_VOID, paymentId, p.getCustomer().getId(), p.getAmount(),
                new ApprovalDtos.NoPayload(), PaymentDtos.PaymentDto.from(p), p.getVersion(),
                "Void the payment of " + Money.format(p.getAmount()) + " from "
                        + p.getCustomer().getName()));
        reverseAllocations(p);
        p.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(p);
        // Defensive: voiding is audited today only through DisputeService.approve, and coalescing
        // makes this free when it is. It stops being free the day a second caller appears (A1).
        changeFeed.changed(SubjectType.PAYMENT, saved.getId(), Change.UPDATED);           // (A1)
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        return saved;
    }

    private void reverseAllocations(Payment p) {
        // Taking money back off an invoice is the same read-modify-write as putting it on, and
        // needs the same locks, in the same order (PPD-01).
        lockCustomer(p.getCustomer().getId());
        lockInvoices(p.getAllocations().stream().map(PaymentAllocation::getInvoice).toList());
        for (PaymentAllocation alloc : new ArrayList<>(p.getAllocations())) {
            Invoice inv = alloc.getInvoice();
            Movement reversed = new Movement(inv, alloc.getAmount(), inv.getPaidAmount(), inv.getStatus());
            inv.setPaidAmount(inv.getPaidAmount().subtract(alloc.getAmount()));
            if (inv.getPaidAmount().signum() < 0) inv.setPaidAmount(BigDecimal.ZERO);
            InvoiceService.recomputeStatus(inv);
            invoiceRepository.save(inv);
            auditMovements(p, List.of(reversed), "PAYMENT_REVERSED");
        }
        p.getAllocations().clear();
        if (p.getCreditApplied() != null && p.getCreditApplied().signum() > 0) {
            Customer c = p.getCustomer();
            BigDecimal newCredit = c.getCreditBalance().subtract(p.getCreditApplied());
            c.setCreditBalance(newCredit.signum() < 0 ? BigDecimal.ZERO : newCredit);
            customerRepository.save(c);
            // LOAD-BEARING: taking a payment back off the book takes its credit with it, and the
            // audit rows this method writes are against INVOICE, never CUSTOMER (A1).
            changeFeed.changed(SubjectType.CUSTOMER, c.getId(), Change.UPDATED);          // (A1)
        }
        p.setCreditApplied(BigDecimal.ZERO);
    }

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
        Money.requireCents(newAmount, "Amount");
        // The customer's lock before either half of the move, so an approval and a payment on the
        // same customer cannot interleave between the reversal and the re-application (PPD-01).
        lockCustomer(p.getCustomer().getId());
        // max(before, after) and not the difference: this method un-applies the entire old amount
        // (:314) and re-applies the new one across EVERY invoice of the customer (:318-321), so a
        // delta rule would score reversing fifty lakh and re-applying fifty lakh and one rupee
        // onto different invoices as a one-rupee change (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.PAYMENT_UPDATE_AMOUNT, paymentId, p.getCustomer().getId(),
                p.getAmount().max(newAmount),
                new ApprovalDtos.AmountChange(newAmount, method, notes),
                PaymentDtos.PaymentDto.from(p), p.getVersion(),
                "Change the payment of " + Money.format(p.getAmount()) + " from "
                        + p.getCustomer().getName() + " to " + Money.format(newAmount)));
        reverseAllocations(p);
        p.setAmount(newAmount);
        if (method != null) p.setMethod(method);
        if (notes != null) p.setNotes(notes);
        List<Invoice> targets = new ArrayList<>(
                invoiceRepository.findByCustomerIdForUpdate(p.getCustomer().getId()));
        targets.sort(OLDEST_FIRST);
        List<Movement> applied = applyTo(p, targets, newAmount);
        Payment saved = paymentRepository.save(p);
        auditMovements(saved, applied, "PAYMENT_APPLIED");
        // Defensive, exactly as in voidPayment: auditMovements writes against INVOICE, so nothing
        // here says the PAYMENT itself changed unless a dispute approval happens to say it (A1).
        changeFeed.changed(SubjectType.PAYMENT, saved.getId(), Change.UPDATED);           // (A1)
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's payment answers exactly as a missing one does, which is what
        // requireInBook already does for a POC outside their book (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(p.getCustomer().getId())) {
            throw new NotFoundException("Payment not found");
        }
        requireInBook(id);
        return p;
    }

    private void requireInBook(Long id) {
        if (!queryExecutor.inScope(Payment.class, TableSchemas.PAYMENTS, id,
                scopeResolver.forPayments().predicates())) {
            throw new NotFoundException("Payment not found");
        }
    }

    /**
     * WHERE A PAYMENT LIST READS FROM (B3). The InvoiceService.invoiceSource() shape exactly: live
     * it is the payments table, its live schema and the POC book; under {@code ?asOf} it is the
     * interval mirror, its as-of twin, and the SAME book — the book predicate names
     * {@code collectionPocUserId}, a flat Long both roots carry, which is what B3-BOOKROOT was for.
     *
     * <p>{@code AsOf.at(T)} LEADS THE SCOPE LIST and is the line that makes the list count
     * PAYMENTS rather than edits of payments: nothing else filters the root.
     *
     * <p>IT ADDS NO REGION PREDICATE — the axis is injected once by the executor for every root and
     * is already as-of correct (blueprint conflict 1, B1, B3).
     *
     * <p>{@code customer} drops out of the fetch list because a mirror has no Customer to walk —
     * the account's name as of then is a column on the row — while {@code collectionPoc} stays,
     * because the person is NOT mirrored and renders as they are today (clause a.3).
     */
    public AsOfSource<PaymentView> paymentSource() {
        ScopeResolver.Scope book = scopeResolver.forPayments();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(Payment.class, TableSchemas.PAYMENTS, book.predicates(),
                    book.lockedFilters(), List.of("customer", "collectionPoc"));
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(PaymentHistory.class, HistorySchemas.PAYMENTS,
                List.copyOf(scope), List.copyOf(locked), List.of("collectionPoc"));
    }

    /** The branch an as-of row cannot answer for itself; filled from R7's ledger (B3, B1). */
    private void placeRegions(List<? extends PaymentView> rows) {
        if (!AsOfContext.isActive()) return;
        List<PaymentHistory> mirrors = rows.stream()
                .filter(PaymentHistory.class::isInstance).map(PaymentHistory.class::cast).toList();
        if (mirrors.isEmpty()) return;
        Map<Long, RegionPlacements.Placement> placements = regionPlacements.at(
                mirrors.stream().map(PaymentHistory::getCustomerId).filter(Objects::nonNull).toList(),
                AsOfContext.date());
        for (PaymentHistory row : mirrors) {
            RegionPlacements.Placement placed = placements.get(row.getCustomerId());
            // A setter on a @Transient field does not dirty the managed row, which is why these
            // two are transient: a persisted field here would be flushed and would rewrite history
            // in order to answer a question about it (B3).
            row.setRegionId(placed == null ? null : placed.regionId());
            row.setRegionName(placed == null ? null : placed.regionName());
        }
    }

    /** Guarded outside the call because AsOfContext.instant() throws when nothing is open (B3). */
    private void markDrift() {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(PaymentHistory.class, AsOfContext.instant());
    }

    /**
     * THE FAT PART OF A PAYMENT ROW, BATCHED (B3).
     *
     * <p>A payment carries its allocations and its account's credit balance on every LIST row. The
     * live path answers both off the loaded entity; the as-of path cannot, because the mirror maps
     * a mirrored foreign key as a flat Long with nothing to walk to. So a whole page costs three
     * queries here — the allocation mirrors in force at T, the invoice versions those allocations
     * name, and the account versions — rather than three per row.
     *
     * <p>AN ALLOCATION WHOSE INVOICE HAS NO VERSION IN FORCE AT T still appears, carrying its
     * amount and nulls for the invoice's own figures. That is the "not asked" convention rather
     * than a silent drop: money was applied to something, and reporting the row as absent would
     * make a past payment look smaller than it was.
     */
    private List<PaymentDtos.PaymentDto> toDtos(List<? extends PaymentView> payments,
                                                boolean includePoc, Set<Long> held) {
        // WHICH BRANCH is internal: a customer login gets B1's two slots empty, the same two
        // TableSchema.visibleTo drops from their column list, so the payload and the schema agree
        // (B1, AUTH-08).
        boolean region = scopeResolver.canSeeRegion();
        if (!AsOfContext.isActive()) {
            return payments.stream()
                    .map(p -> PaymentDtos.PaymentDto.from((Payment) p, includePoc,
                            held.contains(p.getId())))
                    .map(row -> region ? row : row.withoutRegion())
                    .toList();
        }
        Instant at = AsOfContext.instant();
        List<Long> ids = payments.stream().map(PaymentView::getId).toList();

        Map<Long, List<PaymentAllocationHistory>> byPayment = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (PaymentAllocationHistory a : paymentAllocationHistoryRepository.inForce(ids, at)) {
                byPayment.computeIfAbsent(a.getPaymentId(), k -> new ArrayList<>()).add(a);
            }
        }
        List<Long> invoiceIds = byPayment.values().stream().flatMap(List::stream)
                .map(PaymentAllocationHistory::getInvoiceId).filter(Objects::nonNull).distinct().toList();
        Map<Long, InvoiceHistory> invoices = new LinkedHashMap<>();
        if (!invoiceIds.isEmpty()) {
            for (InvoiceHistory i : invoiceHistoryRepository.inForce(invoiceIds, at)) {
                invoices.put(i.getId(), i);
            }
        }
        List<Long> customerIds = payments.stream().map(PaymentView::getCustomerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, BigDecimal> credit = new LinkedHashMap<>();
        if (!customerIds.isEmpty()) {
            for (CustomerHistory c : customerHistoryRepository.inForce(customerIds, at)) {
                credit.put(c.getId(), c.getCreditBalance());
            }
        }

        return payments.stream().map(p -> PaymentDtos.PaymentDto.from(p,
                byPayment.getOrDefault(p.getId(), List.<PaymentAllocationHistory>of()).stream()
                        .map(a -> paidInvoice(a, invoices.get(a.getInvoiceId()))).toList(),
                credit.get(p.getCustomerId()), includePoc, held.contains(p.getId())))
                .map(row -> region ? row : row.withoutRegion())
                .toList();
    }

    /** One allocation, rendered against the invoice as it stood on the date asked about (B3). */
    private static PaymentDtos.PaidInvoiceDto paidInvoice(PaymentAllocationHistory a,
                                                          InvoiceHistory inv) {
        if (inv == null) {
            return new PaymentDtos.PaidInvoiceDto(a.getInvoiceId(), null, null, null, null, null,
                    a.getAmount());
        }
        return new PaymentDtos.PaidInvoiceDto(inv.getId(), inv.getInvoiceNumber(), inv.getTotal(),
                inv.getPaidAmount(), inv.getBalance(), inv.getStatus(), a.getAmount());
    }

    /**
     * ONE PAYMENT, LIVE OR AS OF A DATE — and 404, never 403, when it did not exist then (B3).
     * The InvoiceService.detail shape: the same mirror, the same executor, the same scope list and
     * an {@code id:eq:} filter, so there is no second copy of {@link #get}'s checks to drift.
     */
    @Transactional(readOnly = true)
    public PaymentDtos.PaymentDto detail(Long id, boolean includePoc) {
        if (!AsOfContext.isActive()) {
            PaymentDtos.PaymentDto live =
                    PaymentDtos.PaymentDto.from(get(id), includePoc, approvalPending(id));
            return scopeResolver.canSeeRegion() ? live : live.withoutRegion();
        }
        Long callerCustomer = currentUser.customerIdOrNull();
        AsOfSource<PaymentView> source = paymentSource();
        List<? extends PaymentView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()
                || (callerCustomer != null && !callerCustomer.equals(rows.get(0).getCustomerId()))) {
            throw new NotFoundException("Payment not found");
        }
        placeRegions(rows);
        markDrift();
        return toDtos(rows, includePoc,
                approvalPending(id) ? Set.of(id) : Set.<Long>of()).get(0);
    }

    /**
     * The findAll() branch this took for a null customerId is DELETED: it read every payment in
     * the company past the book and past the region predicate, and it had no caller at all. A
     * finder that answers "everything" is how the next endpoint written here would be unscoped
     * without anyone deciding that it should be (B1).
     */
    @Transactional(readOnly = true)
    public List<Payment> list(Long customerId) {
        if (customerId == null) throw new BadRequestException("Name a customer to list payments for");
        return paymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDtos.PaymentDto> page(TableQuery query) {
        AsOfSource<PaymentView> source = paymentSource();
        boolean poc = scopeResolver.canSeePoc();
        var page = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch());
        placeRegions(page.content());
        markDrift();
        // One query for the whole page, and none at all for an empty one. Under an as-of date it
        // asks the decision LOG instead, inside openTargetIds, so this line does not move (B2, B3).
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.PAYMENT,
                page.content().stream().map(PaymentView::getId).toList());
        return PageResponse.of(toDtos(page.content(), poc, held),
                query, page.total(), source.locked(), regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<PaymentView> source = paymentSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /**
     * The rows behind an export. {@code ? extends PaymentView} and not {@code Payment}, because
     * under {@code ?asOf} these are mirror rows and the caller renders them through the view (B3).
     */
    @Transactional(readOnly = true)
    public List<? extends PaymentView> allMatching(TableQuery query) {
        AsOfSource<PaymentView> source = paymentSource();
        List<? extends PaymentView> rows = queryExecutor.run(source.type(), source.schema(),
                query, source.scope(), source.fetch()).content();
        placeRegions(rows);
        markDrift();
        return rows;
    }

    @Transactional(readOnly = true)
    public PaymentDtos.PaymentSummaryTiles tiles(TableQuery query) {
        AsOfSource<PaymentView> source = paymentSource();
        markDrift();
        // The seven selection lambdas are UNCHANGED: amount, creditApplied, status and the null
        // collectionPoc all resolve on the mirror under the same name and the same Java type, and
        // the approval EXISTS switched to the decision log inside ApprovalSchemas (B3).
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(), query,
                source.scope(), (root, q, cb) -> {
                    Expression<BigDecimal> collected = cb.<BigDecimal>selectCase()
                            .when(cb.equal(root.get("status"), PaymentStatus.VOIDED),
                                    cb.literal(BigDecimal.ZERO))
                            .otherwise(root.<BigDecimal>get("amount"));
                    Expression<BigDecimal> credit = cb.<BigDecimal>selectCase()
                            .when(cb.equal(root.get("status"), PaymentStatus.VOIDED),
                                    cb.literal(BigDecimal.ZERO))
                            .otherwise(root.<BigDecimal>get("creditApplied"));
                    return List.of(
                            cb.count(root.get("id")),
                            cb.coalesce(cb.sum(collected), BigDecimal.ZERO),
                            cb.coalesce(cb.sum(credit), BigDecimal.ZERO),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PaymentStatus.ACTIVE)),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PaymentStatus.VOIDED)),
                            Aggregates.countWhen(cb, cb.isNull(root.get("collectionPoc"))),
                            // Inside the SAME aggregate, so the tile costs no extra round trip
                            // and can never disagree with the approvalPending filter chip (B2).
                            Aggregates.countWhen(cb, ApprovalSchemas.existsOpenPending(
                                    root, q, cb, PendingTargetType.PAYMENT)));
                });
        return new PaymentDtos.PaymentSummaryTiles(
                Aggregates.asLong(row[0]),
                Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]), Aggregates.asLong(row[5]),
                Aggregates.asLong(row[6]));
    }

    /**
     * Is there a change waiting on this one payment? One indexed lookup on the sentinel column;
     * a decided change releases its key, so only a change still waiting is found here (B2).
     */
    @Transactional(readOnly = true)
    public boolean approvalPending(Long id) {
        // UNDER AN AS-OF DATE THE SENTINEL CANNOT ANSWER IT. pending_key is RELEASED the moment a
        // change is decided, so "is one waiting now" is the only question it can be asked; "was
        // one waiting THEN" is a question for the decision log, which pending_changes already is.
        // openTargetIds makes that switch in one place for the whole application (B2, B3).
        if (AsOfContext.isActive()) {
            return pendingChangeRepository.openTargetIds(PendingTargetType.PAYMENT, List.of(id))
                    .contains(id);
        }
        return pendingChangeRepository.existsByPendingKey(
                PendingChange.keyOf(PendingTargetType.PAYMENT, id));
    }

    /**
     * Is ANYTHING waiting on this account? Not just a CUSTOMER-targeted change: the read this
     * answers is the credit balance a cashier is about to spend, and it is a held PAYMENT_VOID —
     * a payment target, not a customer one — that would misstate it (B2).
     */
    @Transactional(readOnly = true)
    public boolean approvalPendingOnCustomer(Long customerId) {
        // Same switch, same reason, and made in the repository so the two spellings of "was one
        // waiting then" cannot drift: under an as-of date a decided change has released its
        // PENDING status, and only the decision log still knows it was waiting (B2, B3).
        return pendingChangeRepository.anyOutstandingOnCustomer(customerId);
    }
}
