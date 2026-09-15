package com.geneinvoice.promise;

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
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the promise lifecycle. Status is never set by hand except through
 * {@link #override}: {@link #evaluate} recomputes it from the current payment and invoice facts,
 * which makes it idempotent and safe to re-run after a void, an invoice edit or a cancellation
 * (AC-B6).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentPromiseService {

    public static final String ENTITY = "PROMISE";
    static final String NOTIF_BROKEN = "PROMISE_BROKEN";

    private final PaymentPromiseRepository promiseRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    // ---- lifecycle -------------------------------------------------------------

    @Transactional
    public PromiseDtos.PromiseDto create(PromiseDtos.CreatePromiseRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        if (req.promisedDate() == null) {
            throw new BadRequestException("Promised date is required");
        }

        User poc = resolveCollectionPoc(req.collectionPocUserId(), customer.getId());
        Set<Invoice> invoices = resolveInvoices(req.invoiceIds(), customer.getId(), Set.of());

        PaymentPromise promise = PaymentPromise.builder()
                .customer(customer)
                .amount(req.amount())
                .promisedDate(req.promisedDate())
                .collectionPoc(poc)
                .notes(req.notes())
                .status(PromiseStatus.OPEN)
                .fulfilledAmount(BigDecimal.ZERO)
                .invoices(invoices)
                .createdByUserId(currentUser.require().getId())
                .build();
        promise = promiseRepository.save(promise);

        auditService.record(ENTITY, promise.getId(), "PROMISE_CREATED", null, snapshot(promise),
                currentUser.require().getId(), null, req.notes());

        evaluate(promise);
        return toDto(promiseRepository.save(promise));
    }

    @Transactional
    public PromiseDtos.PromiseDto update(Long id, PromiseDtos.UpdatePromiseRequest req) {
        PaymentPromise promise = get(id);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be edited");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        Object before = snapshot(promise);

        promise.setAmount(req.amount());
        promise.setPromisedDate(req.promisedDate());
        promise.setNotes(req.notes());
        // Only an actual change of POC is validated, so a promise whose POC has since been
        // deactivated can still be edited (AC-A5).
        Long previousPocId = promise.getCollectionPoc() == null ? null : promise.getCollectionPoc().getId();
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousPocId)) {
            User poc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION);
            promise.setCollectionPoc(poc);
            pocService.notifyAssignee(poc, PocType.COLLECTION,
                    "a payment promise from " + promise.getCustomer().getName(),
                    "/promises/" + promise.getId());
        }
        if (req.invoiceIds() != null) {
            Set<Long> linked = promise.getInvoices().stream().map(Invoice::getId)
                    .collect(java.util.stream.Collectors.toSet());
            promise.setInvoices(resolveInvoices(req.invoiceIds(), promise.getCustomer().getId(), linked));
        }

        evaluate(promise);
        PaymentPromise saved = promiseRepository.save(promise);
        auditService.record(ENTITY, id, "PROMISE_UPDATED", before, snapshot(saved),
                currentUser.require().getId(), null, null);
        return toDto(saved);
    }

    /** Reassigns only the Collection POC. Used by the inline row action and the bulk action. */
    @Transactional
    public PromiseDtos.PromiseDto reassignCollectionPoc(Long id, Long userId) {
        PaymentPromise promise = get(id);
        return update(id, new PromiseDtos.UpdatePromiseRequest(promise.getAmount(),
                promise.getPromisedDate(), userId, promise.getNotes(), null));
    }

    /** Withdraws a promise raised in error. Linked payments are unlinked but never touched (AC-B11). */
    @Transactional
    public PromiseDtos.PromiseDto cancel(Long id, String reason) {
        PaymentPromise promise = get(id);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("Promise is already cancelled");
        }
        Object before = snapshot(promise);
        promise.getPayments().clear();
        promise.setFulfilledAmount(BigDecimal.ZERO);
        promise.setStatus(PromiseStatus.CANCELLED);
        promise.setBrokenNotifiedAt(null);
        PaymentPromise saved = promiseRepository.save(promise);

        auditService.record(ENTITY, id, "PROMISE_CANCELLED", before, snapshot(saved),
                currentUser.require().getId(), null, reason);
        return toDto(saved);
    }

    /** Pins a status by hand when reality disagrees with the arithmetic (AC-B6, AC-B9). */
    @Transactional
    public PromiseDtos.PromiseDto override(Long id, PromiseStatus status, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("An override requires a reason");
        }
        if (status == PromiseStatus.CANCELLED) {
            throw new BadRequestException("Use cancel to withdraw a promise");
        }
        PaymentPromise promise = get(id);
        Object before = snapshot(promise);

        promise.setStatus(status);
        promise.setStatusOverridden(true);
        promise.setOverrideReason(reason);
        promise.setOverriddenByUserId(currentUser.require().getId());
        promise.setOverriddenAt(Instant.now());
        if (status != PromiseStatus.BROKEN) promise.setBrokenNotifiedAt(null);
        PaymentPromise saved = promiseRepository.save(promise);

        auditService.record(ENTITY, id, "PROMISE_STATUS_OVERRIDDEN", before, snapshot(saved),
                currentUser.require().getId(), null, reason);
        if (status == PromiseStatus.BROKEN) notifyBroken(saved);
        return toDto(saved);
    }

    /** Drops a manual override and hands the promise back to automatic tracking. */
    @Transactional
    public PromiseDtos.PromiseDto clearOverride(Long id) {
        PaymentPromise promise = get(id);
        if (!promise.isStatusOverridden()) return toDto(promise);
        Object before = snapshot(promise);
        promise.setStatusOverridden(false);
        promise.setOverrideReason(null);
        promise.setOverriddenByUserId(null);
        promise.setOverriddenAt(null);
        evaluate(promise);
        PaymentPromise saved = promiseRepository.save(promise);
        auditService.record(ENTITY, id, "PROMISE_OVERRIDE_CLEARED", before, snapshot(saved),
                currentUser.require().getId(), null, null);
        return toDto(saved);
    }

    // ---- evaluation ------------------------------------------------------------

    /**
     * Recomputes links, fulfilment and status from current facts. Returns true when anything
     * changed. Safe to call repeatedly and from any direction.
     */
    @Transactional
    public boolean evaluate(PaymentPromise promise) {
        if (promise.getStatus() == PromiseStatus.CANCELLED) return false;

        PromiseStatus previous = promise.getStatus();
        BigDecimal previousFulfilled = promise.getFulfilledAmount();

        linkPayments(promise);
        Fulfilment fulfilment = computeFulfilment(promise);
        BigDecimal fulfilled = fulfilment.total();
        promise.setFulfilledAmount(fulfilled);

        if (promise.isStatusOverridden()) {
            return fulfilled.compareTo(previousFulfilled == null ? BigDecimal.ZERO : previousFulfilled) != 0;
        }

        PromiseStatus target = targetStatus(promise, fulfilment);
        if (target == previous) {
            return fulfilled.compareTo(previousFulfilled == null ? BigDecimal.ZERO : previousFulfilled) != 0;
        }

        promise.setStatus(target);
        if (target != PromiseStatus.BROKEN) promise.setBrokenNotifiedAt(null);
        auditService.record(ENTITY, promise.getId(), "PROMISE_STATUS_CHANGED",
                previous.name(), target.name(), null, null, "Recomputed from payments");
        if (target == PromiseStatus.BROKEN) notifyBroken(promise);
        return true;
    }

    /**
     * Works the status out from current facts. Once the promised date has gone, only money that
     * arrived <em>by</em> that date can keep the promise: paying late does not un-break it, though
     * the payment is still recorded against the fulfilled amount.
     */
    private PromiseStatus targetStatus(PaymentPromise promise, Fulfilment fulfilment) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean datePassed = today.isAfter(promise.getPromisedDate());
        boolean invoiceScoped = !promise.getInvoices().isEmpty();
        List<Invoice> live = liveInvoices(promise);

        // Every invoice the promise named has been cancelled — a dispute erased the debt, so
        // there is nothing left to break over (AC-B6).
        if (invoiceScoped && live.isEmpty()) return PromiseStatus.KEPT;

        boolean invoicesSettled =
                invoiceScoped && live.stream().allMatch(i -> i.getBalance().signum() <= 0);

        if (!datePassed) {
            // On the promised day itself a general promise is measured against what the account
            // still owes, not only against the money linked to it (AC-B7).
            boolean dateReached = !today.isBefore(promise.getPromisedDate());
            boolean complete = invoiceScoped
                    ? invoicesSettled
                    : fulfilment.total().compareTo(promise.getAmount()) >= 0
                            || (dateReached && owedUnderPromise(promise).signum() <= 0);
            if (complete) return PromiseStatus.KEPT;
            return fulfilment.total().signum() > 0 ? PromiseStatus.PARTIALLY_KEPT : PromiseStatus.OPEN;
        }

        // The date has gone. Judge it on what was actually paid in time.
        BigDecimal onTime = fulfilment.onTime();
        boolean keptOnTime = onTime.compareTo(promise.getAmount()) >= 0
                || (invoiceScoped && invoicesSettled && fulfilment.late().signum() == 0)
                || (!invoiceScoped && fulfilment.late().signum() == 0
                        && owedUnderPromise(promise).signum() <= 0);
        if (keptOnTime) return PromiseStatus.KEPT;
        return onTime.signum() > 0 ? PromiseStatus.PARTIALLY_KEPT : PromiseStatus.BROKEN;
    }

    /** Money against a promise, split by whether it arrived before the promised date ran out. */
    private record Fulfilment(BigDecimal onTime, BigDecimal late) {
        BigDecimal total() {
            return onTime.add(late);
        }
    }

    /**
     * Attaches every active payment that settles what this promise covers, and detaches any that
     * has since been voided (AC-B3, AC-B6).
     */
    private void linkPayments(PaymentPromise promise) {
        promise.getPayments().removeIf(p -> p.getStatus() != PaymentStatus.ACTIVE);

        Set<Long> covered = promise.getInvoices().stream().map(Invoice::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Instant since = promise.getCreatedAt() == null ? Instant.EPOCH : promise.getCreatedAt();

        for (Payment payment : paymentRepository.findByCustomerIdOrderByPaidAtDesc(
                promise.getCustomer().getId())) {
            if (payment.getStatus() != PaymentStatus.ACTIVE) continue;
            if (payment.getPaidAt() != null && payment.getPaidAt().isBefore(since)) continue;
            if (covered.isEmpty()) {
                promise.getPayments().add(payment);
            } else if (payment.getAllocations().stream()
                    .anyMatch(a -> covered.contains(a.getInvoice().getId()))) {
                promise.getPayments().add(payment);
            }
        }
    }

    private Fulfilment computeFulfilment(PaymentPromise promise) {
        Set<Long> covered = promise.getInvoices().stream()
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
        Instant deadline = endOfPromisedDate(promise);

        BigDecimal onTime = BigDecimal.ZERO;
        BigDecimal late = BigDecimal.ZERO;
        for (Payment p : promise.getPayments()) {
            if (p.getStatus() != PaymentStatus.ACTIVE) continue;
            BigDecimal contribution = BigDecimal.ZERO;
            if (covered.isEmpty()) {
                contribution = p.getAmount();
            } else {
                for (PaymentAllocation a : p.getAllocations()) {
                    if (covered.contains(a.getInvoice().getId())) {
                        contribution = contribution.add(a.getAmount());
                    }
                }
            }
            if (contribution.signum() == 0) continue;
            if (p.getPaidAt() != null && p.getPaidAt().isAfter(deadline)) {
                late = late.add(contribution);
            } else {
                onTime = onTime.add(contribution);
            }
        }
        return new Fulfilment(onTime, late);
    }

    /** The last instant that still counts as paying by the promised date. */
    private Instant endOfPromisedDate(PaymentPromise promise) {
        return promise.getPromisedDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private List<Invoice> liveInvoices(PaymentPromise promise) {
        return promise.getInvoices().stream()
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .toList();
    }

    /**
     * What the customer still owes on the invoices a general promise answers for: those dated by
     * the end of the promised date, and any that already existed when the promise was made
     * (AC-B7). An invoice raised after both is a new debt the promise never covered, so it cannot
     * break a promise that was already kept.
     */
    private BigDecimal owedUnderPromise(PaymentPromise promise) {
        Instant deadline = endOfPromisedDate(promise);
        Instant made = promise.getCreatedAt();
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice inv : invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(promise.getCustomer().getId())) {
            if (inv.getStatus() == InvoiceStatus.CANCELLED) continue;
            boolean datedInTime = inv.getInvoiceDate().isBefore(deadline);
            boolean existedWhenMade = made == null || inv.getCreatedAt() == null
                    || !inv.getCreatedAt().isAfter(made);
            if (!datedInTime && !existedWhenMade) continue;
            BigDecimal balance = inv.getBalance();
            if (balance.signum() > 0) total = total.add(balance);
        }
        return total;
    }

    // ---- re-entry points -------------------------------------------------------

    /**
     * Links a payment to promises the cashier picked by hand, then re-evaluates them (US-B3).
     * Auto-linking still happens in {@link #evaluate}; this only covers the deliberate case.
     */
    @Transactional
    public void attachPayment(List<Long> promiseIds, Payment payment) {
        if (promiseIds == null || promiseIds.isEmpty()) return;
        for (Long promiseId : promiseIds) {
            PaymentPromise promise = promiseRepository.findById(promiseId)
                    .orElseThrow(() -> new NotFoundException("Payment promise not found: " + promiseId));
            if (!promise.getCustomer().getId().equals(payment.getCustomer().getId())) {
                throw new BadRequestException("Promise " + promiseId + " belongs to a different customer");
            }
            if (promise.getStatus() == PromiseStatus.CANCELLED) {
                throw new BadRequestException("Promise " + promiseId + " has been cancelled");
            }
            promise.getPayments().add(payment);
            evaluate(promise);
            promiseRepository.save(promise);
        }
    }

    /** Re-evaluates every live promise of a customer. Called after any payment or invoice change. */
    @Transactional
    public void reevaluateForCustomer(Long customerId) {
        if (customerId == null) return;
        for (PaymentPromise promise : promiseRepository.findLiveByCustomer(customerId)) {
            if (evaluate(promise)) promiseRepository.save(promise);
        }
    }

    /** Re-evaluates promises touching one invoice, plus the rest of that customer's book. */
    @Transactional
    public void reevaluateForInvoice(Long invoiceId) {
        invoiceRepository.findById(invoiceId)
                .ifPresent(inv -> reevaluateForCustomer(inv.getCustomer().getId()));
    }

    /**
     * Flips overdue open promises to BROKEN so list results, filters and totals are right even for
     * a user who never opens the record (AC-B5).
     */
    @Transactional
    public int sweepOverdue() {
        List<PaymentPromise> overdue = promiseRepository.findOverdueOpen(LocalDate.now(ZoneOffset.UTC));
        int changed = 0;
        for (PaymentPromise promise : overdue) {
            if (evaluate(promise)) {
                promiseRepository.save(promise);
                changed++;
            }
        }
        if (changed > 0) log.info("Promise sweep marked {} promise(s) broken or updated", changed);
        return changed;
    }

    // ---- reads -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentPromise get(Long id) {
        PaymentPromise promise = promiseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(promise.getCustomer().getId())) {
            throw new AccessDeniedException("Not allowed");
        }
        return promise;
    }

    /** Loads and renders in one transaction; the links are lazy and must not outlive it. */
    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto dto(Long id) {
        return toDto(get(id));
    }

    @Transactional(readOnly = true)
    public List<PaymentPromise> listForCustomer(Long customerId) {
        return promiseRepository.findByCustomerIdOrderByPromisedDateDesc(customerId);
    }

    @Transactional(readOnly = true)
    public List<PaymentPromise> listForInvoice(Long invoiceId) {
        return promiseRepository.findByInvoiceId(invoiceId);
    }

    // ---- list, tiles, DTOs -----------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PromiseDtos.PromiseDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPromises();
        var page = queryExecutor.run(PaymentPromise.class, TableSchemas.PROMISES, query,
                scope.predicates(), List.of("customer", "collectionPoc"));
        return PageResponse.of(page.content().stream().map(this::toDto).toList(),
                query, page.total(), scope.lockedFilters());
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        return queryExecutor.ids(PaymentPromise.class, TableSchemas.PROMISES, query,
                scopeResolver.forPromises().predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<PaymentPromise> allMatching(TableQuery query) {
        return queryExecutor.run(PaymentPromise.class, TableSchemas.PROMISES, query,
                scopeResolver.forPromises().predicates(), List.of("customer", "collectionPoc")).content();
    }

    /**
     * Tiles over the whole filtered set. Each promise contributes to exactly one status bucket, so
     * a payment fulfilling several promises cannot double-count (AC-B12, AC-E1).
     */
    @Transactional(readOnly = true)
    public PromiseDtos.PromiseSummaryDto tiles(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPromises();
        Object[] row = queryExecutor.aggregate(PaymentPromise.class, TableSchemas.PROMISES, query,
                scope.predicates(), (root, q, cb) -> {
                    var amount = root.<BigDecimal>get("amount");
                    return List.of(
                            cb.count(root.get("id")),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PromiseStatus.OPEN)),
                            Aggregates.sumWhen(cb, cb.equal(root.get("status"), PromiseStatus.OPEN), amount),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PromiseStatus.KEPT)),
                            Aggregates.sumWhen(cb, cb.equal(root.get("status"), PromiseStatus.KEPT), amount),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PromiseStatus.PARTIALLY_KEPT)),
                            Aggregates.sumWhen(cb, cb.equal(root.get("status"), PromiseStatus.PARTIALLY_KEPT), amount),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PromiseStatus.BROKEN)),
                            Aggregates.sumWhen(cb, cb.equal(root.get("status"), PromiseStatus.BROKEN), amount),
                            Aggregates.countWhen(cb, cb.equal(root.get("status"), PromiseStatus.CANCELLED)),
                            Aggregates.sumWhen(cb, cb.notEqual(root.get("status"), PromiseStatus.CANCELLED), amount),
                            cb.coalesce(cb.sum(root.<BigDecimal>get("fulfilledAmount")), BigDecimal.ZERO));
                });
        return new PromiseDtos.PromiseSummaryDto(
                Aggregates.asLong(row[0]),
                Aggregates.asLong(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asMoney(row[4]),
                Aggregates.asLong(row[5]), Aggregates.asMoney(row[6]),
                Aggregates.asLong(row[7]), Aggregates.asMoney(row[8]),
                Aggregates.asLong(row[9]),
                Aggregates.asMoney(row[10]), Aggregates.asMoney(row[11]));
    }

    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto toDto(PaymentPromise p) {
        boolean showPoc = scopeResolver.canSeePoc();
        return new PromiseDtos.PromiseDto(
                p.getId(), p.getCustomer().getId(), p.getCustomer().getName(),
                p.getAmount(), p.getFulfilledAmount(), p.getRemainingAmount(),
                p.getPromisedDate(), p.getStatus(), p.isStatusOverridden(),
                p.getOverrideReason(), p.getOverriddenByUserId(), p.getOverriddenAt(),
                showPoc ? PocDtos.PocUserDto.from(p.getCollectionPoc()) : null,
                p.getNotes(),
                p.getInvoices().stream()
                        .map(i -> new PromiseDtos.PromiseInvoiceDto(i.getId(), i.getInvoiceNumber(),
                                i.getTotal(), i.getBalance(), i.getStatus().name()))
                        .sorted(java.util.Comparator.comparing(PromiseDtos.PromiseInvoiceDto::id))
                        .toList(),
                p.getPayments().stream()
                        .map(pay -> new PromiseDtos.PromisePaymentDto(pay.getId(), pay.getAmount(),
                                pay.getPaidAt(), pay.getMethod(), pay.getStatus().name()))
                        .sorted(java.util.Comparator.comparing(PromiseDtos.PromisePaymentDto::id))
                        .toList(),
                p.getCreatedByUserId(), p.getCreatedAt(), p.getUpdatedAt());
    }

    // ---- helpers ---------------------------------------------------------------

    private User resolveCollectionPoc(Long requested, Long customerId) {
        if (requested != null) {
            return pocService.requireAssignable(requested, PocType.COLLECTION);
        }
        return pocService.primaryFor(customerId, PocType.COLLECTION)
                .orElseThrow(() -> new BadRequestException(
                        "A Collection POC is required — this customer has no primary Collection POC, "
                                + "so pick one explicitly"));
    }

    /**
     * Loads the invoices a promise covers. A cancelled invoice cannot be newly promised against,
     * but one already linked when it was cancelled stays acceptable, so the promise can still be
     * edited afterwards.
     */
    private Set<Invoice> resolveInvoices(List<Long> ids, Long customerId, Set<Long> alreadyLinked) {
        Set<Invoice> resolved = new LinkedHashSet<>();
        if (ids == null || ids.isEmpty()) return resolved;
        List<Invoice> found = invoiceRepository.findAllById(new ArrayList<>(ids));
        if (found.size() != new LinkedHashSet<>(ids).size()) {
            throw new NotFoundException("One or more invoices were not found");
        }
        for (Invoice inv : found) {
            if (!inv.getCustomer().getId().equals(customerId)) {
                throw new BadRequestException("Invoice " + inv.getInvoiceNumber()
                        + " belongs to a different customer");
            }
            if (inv.getStatus() == InvoiceStatus.CANCELLED && !alreadyLinked.contains(inv.getId())) {
                throw new BadRequestException("Invoice " + inv.getInvoiceNumber()
                        + " is cancelled and cannot be promised against");
            }
            resolved.add(inv);
        }
        return resolved;
    }

    private void notifyBroken(PaymentPromise promise) {
        if (promise.getBrokenNotifiedAt() != null) return;
        if (promise.getCollectionPoc() == null) return;
        notificationService.notify(
                promise.getCollectionPoc().getId(),
                NOTIF_BROKEN,
                "Promise broken — " + promise.getCustomer().getName(),
                promise.getCustomer().getName() + " promised "
                        + promise.getAmount().toPlainString() + " by " + promise.getPromisedDate()
                        + " and it has not been paid.",
                "/promises/" + promise.getId());
        promise.setBrokenNotifiedAt(Instant.now());
    }

    /** Compact form written into the audit trail. */
    private Object snapshot(PaymentPromise p) {
        return new PromiseAuditSnapshot(p.getId(), p.getCustomer().getId(), p.getAmount(),
                p.getPromisedDate(), p.getStatus(), p.getFulfilledAmount(),
                p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId(),
                p.getNotes(),
                p.getInvoices().stream().map(Invoice::getId).sorted().toList(),
                p.isStatusOverridden(), p.getOverrideReason());
    }

    public record PromiseAuditSnapshot(Long id, Long customerId, BigDecimal amount,
                                       LocalDate promisedDate, PromiseStatus status,
                                       BigDecimal fulfilledAmount, Long collectionPocUserId,
                                       String notes, List<Long> invoiceIds,
                                       boolean statusOverridden, String overrideReason) {}
}
