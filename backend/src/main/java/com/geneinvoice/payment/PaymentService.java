package com.geneinvoice.payment;

import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.Gated;
import com.geneinvoice.approval.MakerChecker;
import com.geneinvoice.approval.PendingChangeScope;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
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
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.user.User;
import jakarta.persistence.criteria.Expression;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String ENTITY = "PAYMENT";

    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final RecordAccess recordAccess;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final PaymentPromiseService promiseService;
    private final Records records;
    private final AutomationEvents automationEvents;
    private final MakerChecker makerChecker;
    private final PendingChangeScope pendingChangeScope;
    private final Clock clock;

    @Transactional
    public Payment record(PaymentDtos.CreatePaymentRequest req) {
        return recordOrHold(req).appliedOrThrow();
    }

    /**
     * Records the payment, or, above the region's PAYMENT_RECORD limit, holds it for someone else to approve and
     * leaves every invoice, the customer's credit and the payments list as they were (B2). The money was received
     * when it was recorded, so an approved payment keeps the time it was asked for as its payment time.
     */
    @Transactional
    public Outcome<Payment> recordOrHold(PaymentDtos.CreatePaymentRequest req) {
        // The first lock of the money path, before anything of this customer's is read: two
        // cashiers recording a payment for one customer at the same instant queue here instead of
        // both reading the same paid amounts and credit balance and each writing their own
        // figures over the other's, which loses one payment's money outright (PPD-01).
        Customer customer = lockCustomer(req.customerId());
        ViewScopeHolder.requireLive();
        // Book-aware: outside the caller's book the customer is missing, and nothing is written (SC-12).
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customer.getId(), Region.idOf(customer.getRegion()),
                "Customer not found");
        currentUser.requireIn(Privileges.PAYMENT_MANAGE, Region.idOf(customer.getRegion()));

        User collectionPoc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION,
                Region.idOf(customer.getRegion()));

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
        Optional<PendingChange> held = makerChecker.gate(recordGate(customer, collectionPoc, req));
        if (held.isPresent()) return Outcome.held(held.get());

        Payment payment = Payment.builder()
                .customer(customer)
                .amount(req.amount())
                .method(req.method())
                .notes(req.notes())
                .collectionPoc(collectionPoc)
                .status(PaymentStatus.ACTIVE)
                .region(customer.getRegion())
                .paidAt(ApprovalContext.current().map(ApprovalContext.Applying::requestedAt).orElse(clock.instant()))
                .build();

        List<Movement> applied = applyTo(payment, targets, req.amount());
        Payment saved = paymentRepository.save(payment);
        auditService.record(ENTITY, saved.getId(), "PAYMENT_RECORDED", null,
                PaymentDtos.PaymentDto.from(saved), currentUser.idOrNull(), null, req.notes());
        auditMovements(saved, applied, "PAYMENT_APPLIED");
        automationEvents.created(TriggerEntity.PAYMENT, saved.getId());

        pocService.notifyAssignee(collectionPoc, PocType.COLLECTION,
                "payment #" + saved.getId(), "/payments/" + saved.getId(), Region.idOf(saved.getRegion()));
        promiseService.reevaluateForCustomer(customer.getId());
        return Outcome.applied(saved);
    }

    /** What the approver of a held payment is shown; there is nothing before it. */
    public record RecordView(BigDecimal amount, String method, String notes, List<Long> invoiceIds,
                             List<Long> promiseIds, Long collectionPocUserId) {}

    private static Gated recordGate(Customer customer, User collectionPoc, PaymentDtos.CreatePaymentRequest req) {
        Long pocId = collectionPoc == null ? null : collectionPoc.getId();
        return new Gated(ApprovalOperation.PAYMENT_RECORD, null, "New payment from " + customer.getName(), ENTITY,
                null, null, customer.getId(), customer.getName(), Region.idOf(customer.getRegion()), pocId,
                req.amount(), req, null,
                new RecordView(req.amount(), req.method(), req.notes(), req.invoiceIds(), req.promiseIds(), pocId),
                "Record payment of " + Money.format(req.amount()) + " from " + customer.getName(), false);
    }

    @Transactional
    public Payment update(Long id, PaymentDtos.UpdatePaymentRequest req) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        requireVisible(p);
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.PAYMENT_MANAGE, Region.idOf(p.getRegion()));
        Object before = PaymentDtos.PaymentDto.from(p);

        if (req.notes() != null) p.setNotes(req.notes());
        Long previousId = p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId();
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPocIn(Region.idOf(p.getRegion()))) {
                throw new BadRequestException("You may not change the Collection POC");
            }
            User poc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION,
                    Region.idOf(p.getRegion()));
            p.setCollectionPoc(poc);
            pocService.notifyAssignee(poc, PocType.COLLECTION,
                    "payment #" + p.getId(), "/payments/" + p.getId(), Region.idOf(p.getRegion()));
        }
        Payment saved = paymentRepository.save(p);
        auditService.record(ENTITY, id, "PAYMENT_UPDATED", before, PaymentDtos.PaymentDto.from(saved),
                currentUser.require().getId(), null, null);
        automationEvents.updated(TriggerEntity.PAYMENT, id, "DETAILS");
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
            automationEvents.invoiceMoney(inv.getId(), customer.getId(), "PAYMENT");
        }
        payment.setCreditApplied(remaining);
        if (remaining.signum() > 0) {
            customer.setCreditBalance(customer.getCreditBalance().add(remaining));
            customerRepository.save(customer);
            automationEvents.updated(TriggerEntity.CUSTOMER, customer.getId(), "CREDIT");
        }
        // Never the payment itself here: a new one has no id yet, and its callers publish it (S7).
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
        reverseAllocations(p);
        p.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(p);
        automationEvents.updated(TriggerEntity.PAYMENT, paymentId, "STATUS");
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
            automationEvents.invoiceMoney(inv.getId(), p.getCustomer().getId(), "PAYMENT");
        }
        p.getAllocations().clear();
        if (p.getCreditApplied() != null && p.getCreditApplied().signum() > 0) {
            Customer c = p.getCustomer();
            BigDecimal newCredit = c.getCreditBalance().subtract(p.getCreditApplied());
            c.setCreditBalance(newCredit.signum() < 0 ? BigDecimal.ZERO : newCredit);
            customerRepository.save(c);
            automationEvents.updated(TriggerEntity.CUSTOMER, c.getId(), "CREDIT");
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
        automationEvents.updated(TriggerEntity.PAYMENT, paymentId, "AMOUNT");
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        return saved;
    }

    /** The payment, or as of a date the payment as it was then (not found if it did not exist yet). */
    @Transactional(readOnly = true)
    public Payment get(Long id) {
        Payment p = records.find(Payment.class, id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's payment answers exactly as a missing one does, which is what
        // requireVisible already does for a POC outside their book (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(p.getCustomer().getId())) {
            throw new NotFoundException("Payment not found");
        }
        requireVisible(p);
        return p;
    }

    private void requireVisible(Payment p) {
        scopeResolver.requireVisible(ScopeResolver.Kind.PAYMENT, p.getId(), Region.idOf(p.getRegion()),
                "Payment not found");
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDtos.PaymentDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPayments();
        boolean poc = scopeResolver.canSeePoc();
        var page = queryExecutor.run(Payment.class, TableSchemas.PAYMENTS, query,
                scope.predicates(), List.of("customer", "collectionPoc", "region"));
        Set<Long> pending = pendingChangeScope.pendingOn(ENTITY, page.content().stream().map(Payment::getId).toList());
        return PageResponse.of(page.content().stream()
                        .map(p -> PaymentDtos.PaymentDto.from(p, poc, recordAccess)
                                .withPendingApproval(pending.contains(p.getId())))
                        .toList(),
                query, page.total(), scope);
    }

    /** The ids a bulk action or export may touch: VIEW for an export, MANAGE (and any extras) for a write. */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit, ScopeResolver.Access access, String... alsoRequired) {
        return queryExecutor.ids(Payment.class, TableSchemas.PAYMENTS, query,
                scopeResolver.scope(ScopeResolver.Kind.PAYMENT, access, ScopeResolver.Reach.SELECTED, alsoRequired)
                        .predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Payment> allMatching(TableQuery query) {
        return queryExecutor.run(Payment.class, TableSchemas.PAYMENTS, query,
                scopeResolver.forPayments().predicates(), List.of("customer", "collectionPoc", "region")).content();
    }

    @Transactional(readOnly = true)
    public PaymentDtos.PaymentSummaryTiles tiles(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPayments();
        Object[] row = queryExecutor.aggregate(Payment.class, TableSchemas.PAYMENTS, query,
                scope.predicates(), (root, q, cb) -> {
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
                            Aggregates.countWhen(cb, cb.isNull(root.get("collectionPoc"))));
                });
        return new PaymentDtos.PaymentSummaryTiles(
                Aggregates.asLong(row[0]),
                Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]), Aggregates.asLong(row[5]));
    }
}
