package com.geneinvoice.payment;

import com.geneinvoice.audit.AuditService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final CurrentUser currentUser;
    private final PaymentPromiseService promiseService;
    private final UserRepository userRepository;

    @Transactional
    public Payment record(PaymentDtos.CreatePaymentRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        // Mandatory on create, enforced here rather than only in the form (AC-A2).
        User collectionPoc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION);

        List<Invoice> targets;
        if (req.invoiceIds() != null && !req.invoiceIds().isEmpty()) {
            targets = invoiceRepository.findAllById(req.invoiceIds());
            for (Invoice inv : targets) {
                if (!inv.getCustomer().getId().equals(customer.getId())) {
                    throw new BadRequestException(
                            "Invoice " + inv.getInvoiceNumber() + " does not belong to this customer");
                }
            }
        } else {
            targets = invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customer.getId());
        }

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
        promiseService.attachPayment(req.promiseIds(), saved);
        promiseService.reevaluateForCustomer(customer.getId());
        return saved;
    }

    /** Inline edit from the detail screen: notes and the Collection POC. */
    @Transactional
    public Payment update(Long id, PaymentDtos.UpdatePaymentRequest req) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
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
     * Apply `amount` to outstanding invoices oldest-first; leftover goes to credit. Returns what
     * landed on each invoice, for the caller to audit once the payment has an id.
     */
    private List<Movement> applyTo(Payment payment, List<Invoice> targets, BigDecimal amount) {
        Customer customer = payment.getCustomer();
        List<Invoice> outstanding = new ArrayList<>(targets.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.FULLY_PAID && i.getStatus() != InvoiceStatus.CANCELLED)
                .sorted(Comparator.comparing(Invoice::getInvoiceDate))
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
        reverseAllocations(p);
        p.setStatus(PaymentStatus.VOIDED);
        Payment saved = paymentRepository.save(p);
        // A voided payment can no longer keep a promise (AC-B6).
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        return saved;
    }

    /**
     * Reverse allocations only (does NOT mark status). Used both for void and for re-recording.
     * {@link CreditLedger} keeps the allocations and the credit applied current as money moves
     * through credit, so this takes back exactly what the payment put in. The floor at zero only
     * matters for credit older than the ledger.
     */
    private void reverseAllocations(Payment p) {
        for (PaymentAllocation alloc : new ArrayList<>(p.getAllocations())) {
            Invoice inv = alloc.getInvoice();
            Movement reversed = new Movement(inv, alloc.getAmount(), inv.getPaidAmount(), inv.getStatus());
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
        reverseAllocations(p);
        p.setAmount(newAmount);
        if (method != null) p.setMethod(method);
        if (notes != null) p.setNotes(notes);
        List<Invoice> targets = invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(p.getCustomer().getId());
        List<Movement> applied = applyTo(p, targets, newAmount);
        Payment saved = paymentRepository.save(p);
        auditMovements(saved, applied, "PAYMENT_APPLIED");
        promiseService.reevaluateForCustomer(p.getCustomer().getId());
        return saved;
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
