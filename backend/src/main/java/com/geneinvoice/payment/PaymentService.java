package com.geneinvoice.payment;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.automation.AutomationEntityType;
import com.geneinvoice.automation.AutomationEvents;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Expression;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final AutomationEvents automationEvents;
    private final CurrentUser currentUser;
    private final PaymentPromiseService promiseService;
    private final UserRepository userRepository;

    @Transactional
    public Payment record(PaymentDtos.CreatePaymentRequest req) {
        // The first lock of the money path, before anything of this customer's is read: two
        // cashiers recording a payment for one customer at the same instant queue here instead of
        // both reading the same paid amounts and credit balance and each writing their own
        // figures over the other's, which loses one payment's money outright (PPD-01).
        Customer customer = lockCustomer(req.customerId());

        // Mandatory on create, enforced here rather than only in the form (AC-A2).
        User collectionPoc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION);

        List<Long> chosen = req.invoiceIds() == null ? List.of() : req.invoiceIds();
        // The invoices of promises the cashier ticked are paid first, so the payment counts towards
        // those promises (US-B3); a ticked promise the chosen invoices cannot serve is refused.
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

        BigDecimal creditBefore = customer.getCreditBalance();
        List<Movement> applied = applyTo(payment, targets, req.amount());
        Payment saved = paymentRepository.save(payment);
        auditService.record(ENTITY, saved.getId(), "PAYMENT_RECORDED", null,
                PaymentDtos.PaymentDto.from(saved), currentUser.idOrNull(), null, req.notes());
        auditMovements(saved, applied, "PAYMENT_APPLIED");

        pocService.notifyAssignee(collectionPoc, PocType.COLLECTION,
                "payment #" + saved.getId(), "/payments/" + saved.getId());
        promiseService.reevaluateForCustomer(customer.getId());
        // One insert, inside this transaction, so a rule sees a record that exists and
        // nothing is lost if the consumer is down. No rule is read here (R3).
        automationEvents.recordCreated(AutomationEntityType.PAYMENT, saved.getId());
        // And one for each invoice the money landed on, and for the customer if any of it became
        // credit: the payment is not the only record this save changed (D-73).
        announce(customer, creditBefore, applied);
        return saved;
    }

    /** Inline edit from the detail screen: notes and the Collection POC. */
    @Transactional
    public Payment update(Long id, PaymentDtos.UpdatePaymentRequest req) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        requireInBook(id);
        Object before = PaymentDtos.PaymentDto.from(p);

        if (req.notes() != null) p.setNotes(req.notes());
        // Only an actual change of POC is checked, so a notes edit still saves when the POC has
        // since been deactivated or the editor may not assign POCs (AC-A5).
        Long previousId = p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId();
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousId)) {
            if (!currentUser.canAssignPoc(userRepository)) {
                throw new BadRequestException("You may not change the Collection POC");
            }
            User poc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION);
            p.setCollectionPoc(poc);
            pocService.notifyAssignee(poc, PocType.COLLECTION,
                    "payment #" + p.getId(), "/payments/" + p.getId());
        }
        Payment saved = paymentRepository.save(p);
        auditService.record(ENTITY, id, "PAYMENT_UPDATED", before, PaymentDtos.PaymentDto.from(saved),
                currentUser.require().getId(), null, null);
        automationEvents.recordUpdated(AutomationEntityType.PAYMENT, saved.getId());
        return saved;
    }

    @Transactional
    public Payment reassignCollectionPoc(Long id, Long userId) {
        return update(id, new PaymentDtos.UpdatePaymentRequest(null, userId));
    }

    /** One invoice's paid position before or after a payment was applied to it or taken off it. */
    public record InvoicePaymentAudit(Long paymentId, BigDecimal amount, BigDecimal paidAmount,
                                      BigDecimal balance, InvoiceStatus status) {}

    /** How much of a payment landed on one invoice, and where that invoice stood beforehand. */
    private record Movement(Invoice invoice, BigDecimal amount,
                            BigDecimal paidBefore, InvoiceStatus statusBefore) {}

    /**
     * Writes one audit row per invoice a payment moved, on the invoice itself, so an invoice's
     * History shows every payment that landed on it or was taken back off it.
     */
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

    /**
     * One UPDATED event for each record the payment actually left different, written inside the
     * payment's own transaction exactly as the PAYMENT event is (R1, R3).
     *
     * <p>A payment is the most important thing that happens to an invoice — UNPAID to
     * PARTIALLY_PAID to FULLY_PAID, and back again on a void — and {@code status} is a column the
     * rule editor offers in its WHERE. None of it reached the engine: the invoice rows are written
     * here rather than through {@code InvoiceService}, which is where every other invoice event
     * comes from, so "this invoice has just been paid off" was the one transition no rule could
     * see (D-73). The customer is told for the same reason: its credit balance is a filterable
     * column and a payment moves it.
     *
     * <p>"Actually left different" is the whole of the check, because with the day bucket a record
     * gets one slot per rule per day (R4) and a payment must not spend it on nothing.
     * {@code updateAmount} takes a payment off its invoices and puts it back on; an invoice that
     * ends the transaction where it began was not changed by it, and the first movement recorded
     * against that invoice is what the comparison is made from — not the last, so a reversal and a
     * re-application read as the one net move they are. The credit balance is read the same way,
     * from the figure the customer had before the money was touched: a reversal that floors at
     * zero can land on the balance that was already there, and that is not a change.
     *
     * @param customer the locked customer, still managed, so its balance now is the balance written
     * @param touched  every movement either half made, in the order they were made
     */
    private void announce(Customer customer, BigDecimal creditBefore, List<Movement> touched) {
        Map<Long, Movement> firstTouch = new LinkedHashMap<>();
        for (Movement m : touched) firstTouch.putIfAbsent(m.invoice().getId(), m);
        for (Movement m : firstTouch.values()) {
            Invoice inv = m.invoice();
            if (inv.getPaidAmount().compareTo(m.paidBefore()) != 0 || inv.getStatus() != m.statusBefore()) {
                automationEvents.recordUpdated(AutomationEntityType.INVOICE, inv.getId());
            }
        }
        if (creditBefore.compareTo(customer.getCreditBalance()) != 0) {
            automationEvents.recordUpdated(AutomationEntityType.CUSTOMER, customer.getId());
        }
    }

    private static final Comparator<Invoice> OLDEST_FIRST = Comparator.comparing(Invoice::getInvoiceDate);

    /**
     * The first lock of every money path: the customer whose credit balance and invoices are
     * about to move. Taking it before anything else, and before any invoice of theirs, gives the
     * whole path one lock order, so two requests on one customer queue and never deadlock.
     */
    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    /** The invoices about to be written, locked by ascending id — the second half of that order. */
    private void lockInvoices(List<Invoice> invoices) {
        List<Long> ids = invoices.stream().map(Invoice::getId).filter(java.util.Objects::nonNull).toList();
        if (!ids.isEmpty()) invoiceRepository.findAllByIdForUpdate(ids);
    }

    /**
     * Apply `amount` to the outstanding invoices in the order given (the caller's order: oldest
     * first, or a ticked promise's invoices first); leftover goes to credit. Returns what landed
     * on each invoice, for the caller to audit once the payment has an id.
     */
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
        }
        return moves;
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
        Customer customer = lockCustomer(p.getCustomer().getId());
        BigDecimal creditBefore = customer.getCreditBalance();
        List<Movement> reversed = reverseAllocations(p);
        p.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(p);
        // A voided payment can no longer keep a promise (AC-B6).
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        automationEvents.recordUpdated(AutomationEntityType.PAYMENT, saved.getId());
        // Every invoice the money came back off, and the customer if credit went with it: a void
        // changes an invoice's status as surely as the payment did (D-73).
        announce(customer, creditBefore, reversed);
        return saved;
    }

    /**
     * Reverse allocations only (does NOT mark status). Used both for void and for re-recording.
     * {@link CreditLedger} keeps the allocations and the credit applied current as money moves
     * through credit, so this takes back exactly what the payment put in. The floor at zero only
     * matters for credit older than the ledger.
     */
    private List<Movement> reverseAllocations(Payment p) {
        // Taking money back off an invoice is the same read-modify-write as putting it on, and
        // needs the same locks, in the same order (PPD-01).
        lockCustomer(p.getCustomer().getId());
        lockInvoices(p.getAllocations().stream().map(PaymentAllocation::getInvoice).toList());
        List<Movement> moves = new ArrayList<>();
        for (PaymentAllocation alloc : new ArrayList<>(p.getAllocations())) {
            Invoice inv = alloc.getInvoice();
            Movement reversed = new Movement(inv, alloc.getAmount(), inv.getPaidAmount(), inv.getStatus());
            moves.add(reversed);
            inv.setPaidAmount(inv.getPaidAmount().subtract(alloc.getAmount()));
            if (inv.getPaidAmount().signum() < 0) inv.setPaidAmount(BigDecimal.ZERO);
            InvoiceService.recomputeStatus(inv);
            invoiceRepository.save(inv);
            // Audited now, while the invoice shows the reversal alone; a re-application that
            // follows (an amount change) is audited on its own.
            auditMovements(p, List.of(reversed), "PAYMENT_REVERSED");
        }
        p.getAllocations().clear();
        if (p.getCreditApplied() != null && p.getCreditApplied().signum() > 0) {
            Customer c = p.getCustomer();
            BigDecimal newCredit = c.getCreditBalance().subtract(p.getCreditApplied());
            c.setCreditBalance(newCredit.signum() < 0 ? BigDecimal.ZERO : newCredit);
            customerRepository.save(c);
        }
        p.setCreditApplied(BigDecimal.ZERO);
        return moves;
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
        Money.requireCents(newAmount, "Amount");
        // The customer's lock before either half of the move, so an approval and a payment on the
        // same customer cannot interleave between the reversal and the re-application (PPD-01).
        Customer customer = lockCustomer(p.getCustomer().getId());
        BigDecimal creditBefore = customer.getCreditBalance();
        List<Movement> reversed = reverseAllocations(p);
        p.setAmount(newAmount);
        if (method != null) p.setMethod(method);
        if (notes != null) p.setNotes(notes);
        List<Invoice> targets = new ArrayList<>(
                invoiceRepository.findByCustomerIdForUpdate(p.getCustomer().getId()));
        targets.sort(OLDEST_FIRST);
        List<Movement> applied = applyTo(p, targets, newAmount);
        Payment saved = paymentRepository.save(p);
        auditMovements(saved, applied, "PAYMENT_APPLIED");
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        automationEvents.recordUpdated(AutomationEntityType.PAYMENT, saved.getId());
        // Both halves in one list, so an invoice the reversal and the re-application both touched
        // is judged on where it ended up rather than told about twice (D-73).
        List<Movement> both = new ArrayList<>(reversed);
        both.addAll(applied);
        announce(customer, creditBefore, both);
        return saved;
    }

    /** A customer login reads only its own payments; a POC limited to their book, only theirs. */
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

    @Transactional(readOnly = true)
    public List<Payment> list(Long customerId) {
        return customerId == null
                ? paymentRepository.findAll()
                : paymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId);
    }

    // ---- list, tiles ------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PaymentDtos.PaymentDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPayments();
        boolean poc = scopeResolver.canSeePoc();
        var page = queryExecutor.run(Payment.class, TableSchemas.PAYMENTS, query,
                scope.predicates(), List.of("customer", "collectionPoc"));
        return PageResponse.of(
                page.content().stream().map(p -> PaymentDtos.PaymentDto.from(p, poc)).toList(),
                query, page.total(), scope.lockedFilters());
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        return queryExecutor.ids(Payment.class, TableSchemas.PAYMENTS, query,
                scopeResolver.forPayments().predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Payment> allMatching(TableQuery query) {
        return queryExecutor.run(Payment.class, TableSchemas.PAYMENTS, query,
                scopeResolver.forPayments().predicates(), List.of("customer", "collectionPoc")).content();
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
