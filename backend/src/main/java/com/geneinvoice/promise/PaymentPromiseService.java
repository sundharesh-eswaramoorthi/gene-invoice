package com.geneinvoice.promise;

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
import com.geneinvoice.invoice.InvoiceDates;
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
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final UserRepository userRepository;

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
        Long previousPocId = promise.getCollectionPoc() == null ? null : promise.getCollectionPoc().getId();
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousPocId)) {
            // Moving a promise's Collection POC is the same act as moving a payment's, and needs
            // the same privilege: without this, POC_ASSIGN guarded one list and not the other
            // (PPD-05).
            if (!currentUser.canAssignPoc(userRepository)) {
                throw new BadRequestException("You may not change the Collection POC");
            }
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

    @Transactional
    public PromiseDtos.PromiseDto reassignCollectionPoc(Long id, Long userId) {
        PaymentPromise promise = get(id);
        return update(id, new PromiseDtos.UpdatePromiseRequest(promise.getAmount(),
                promise.getPromisedDate(), userId, promise.getNotes(), null));
    }

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
        promise.setStatusOverridden(false);
        promise.setOverrideReason(null);
        promise.setOverriddenByUserId(null);
        promise.setOverriddenAt(null);
        PaymentPromise saved = promiseRepository.save(promise);

        auditService.record(ENTITY, id, "PROMISE_CANCELLED", before, snapshot(saved),
                currentUser.require().getId(), null, reason);
        reevaluateForCustomer(saved.getCustomer().getId());
        return toDto(saved);
    }

    @Transactional
    public PromiseDtos.PromiseDto override(Long id, PromiseStatus status, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("An override requires a reason");
        }
        if (status == PromiseStatus.CANCELLED) {
            throw new BadRequestException("Use cancel to withdraw a promise");
        }
        PaymentPromise promise = get(id);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be overridden");
        }
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

    @Transactional
    public PromiseDtos.PromiseDto clearOverride(Long id) {
        PaymentPromise promise = get(id);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be changed");
        }
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

    private static final String RECOMPUTED = "Recomputed from payments";

    @Transactional
    public boolean evaluate(PaymentPromise promise) {
        if (promise.getStatus() == PromiseStatus.CANCELLED) return false;
        return evaluateCustomer(promise.getCustomer().getId(), true, RECOMPUTED).contains(promise);
    }

    private List<PaymentPromise> evaluateCustomer(Long customerId, boolean notify, String reason) {
        List<PaymentPromise> live = promiseRepository.findLiveByCustomer(customerId);
        List<Payment> payments = paymentRepository.findByCustomerIdOrderByPaidAtDesc(customerId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.ACTIVE)
                .sorted(Comparator.comparing(Payment::getPaidAt).thenComparing(Payment::getId))
                .toList();
        Map<PaymentPromise, List<Share>> shares = shareOut(live, payments);

        List<PaymentPromise> changed = new ArrayList<>();
        for (PaymentPromise promise : live) {
            if (applyShares(promise, shares.get(promise), payments, notify, reason)) changed.add(promise);
        }
        return changed;
    }

    private boolean applyShares(PaymentPromise promise, List<Share> shares, List<Payment> payments,
                                boolean notify, String reason) {
        PromiseStatus previous = promise.getStatus();
        BigDecimal previousFulfilled =
                promise.getFulfilledAmount() == null ? BigDecimal.ZERO : promise.getFulfilledAmount();
        Set<Payment> previousLinks = new HashSet<>(promise.getPayments());

        relink(promise, shares, payments);
        Fulfilment fulfilment = fulfilment(promise, shares);
        promise.setFulfilledAmount(fulfilment.total());
        boolean moved = fulfilment.total().compareTo(previousFulfilled) != 0
                || !previousLinks.equals(new HashSet<>(promise.getPayments()));

        if (promise.isStatusOverridden()) return moved;
        PromiseStatus target = targetStatus(promise, fulfilment);
        if (target == previous) return moved;

        promise.setStatus(target);
        if (target != PromiseStatus.BROKEN) promise.setBrokenNotifiedAt(null);
        auditService.record(ENTITY, promise.getId(), "PROMISE_STATUS_CHANGED",
                previous.name(), target.name(), null, null, reason);
        if (target == PromiseStatus.BROKEN && notify) notifyBroken(promise);
        return true;
    }

    private PromiseStatus targetStatus(PaymentPromise promise, Fulfilment fulfilment) {
        LocalDate today = InvoiceDates.today();
        boolean datePassed = today.isAfter(promise.getPromisedDate());
        boolean invoiceScoped = !promise.getInvoices().isEmpty();
        List<Invoice> live = liveInvoices(promise);

        if (invoiceScoped && live.isEmpty()) return PromiseStatus.KEPT;

        boolean invoicesSettled =
                invoiceScoped && live.stream().allMatch(i -> i.getBalance().signum() <= 0);

        if (!datePassed) {
            boolean dateReached = !today.isBefore(promise.getPromisedDate());
            // The promised money having arrived keeps the promise whatever it was scoped to: a
            // promise of part of a large invoice, paid in full and on time, is kept the moment
            // the money lands, not only once the date has gone by — the test the date-has-gone
            // branch below already applies to the same facts (PPD-02).
            boolean complete = fulfilment.total().compareTo(promise.getAmount()) >= 0
                    || (invoiceScoped
                            ? invoicesSettled
                            : dateReached && owedUnderPromise(promise).signum() <= 0);
            if (complete) return PromiseStatus.KEPT;
            return fulfilment.total().signum() > 0 ? PromiseStatus.PARTIALLY_KEPT : PromiseStatus.OPEN;
        }

        BigDecimal onTime = fulfilment.onTime();
        boolean keptOnTime = onTime.compareTo(promise.getAmount()) >= 0
                || (invoiceScoped && invoicesSettled && !fulfilment.paidLate())
                || (!invoiceScoped && !fulfilment.paidLate()
                        && owedUnderPromise(promise).signum() <= 0);
        if (keptOnTime) return PromiseStatus.KEPT;
        return onTime.signum() > 0 ? PromiseStatus.PARTIALLY_KEPT : PromiseStatus.BROKEN;
    }

    private record Fulfilment(BigDecimal onTime, BigDecimal late, boolean paidLate) {
        BigDecimal total() {
            return onTime.add(late);
        }
    }

    private record Share(Payment payment, BigDecimal amount) {}

    private Map<PaymentPromise, List<Share>> shareOut(List<PaymentPromise> live, List<Payment> payments) {
        Map<PaymentPromise, List<Share>> shares = new IdentityHashMap<>();
        Map<PaymentPromise, BigDecimal> need = new IdentityHashMap<>();
        for (PaymentPromise p : live) {
            shares.put(p, new ArrayList<>());
            need.put(p, p.getAmount());
        }

        for (Payment pay : payments) {
            List<PaymentPromise> byDate = live.stream()
                    .filter(p -> counts(pay, p))
                    .sorted(Comparator.comparing((PaymentPromise p) -> isLateFor(pay, p))
                            .thenComparing(PaymentPromise::getPromisedDate)
                            .thenComparing(PaymentPromise::getId))
                    .toList();
            BigDecimal pool = pay.getAmount();
            for (PaymentAllocation a : pay.getAllocations()) {
                BigDecimal onInvoice = a.getAmount();
                for (PaymentPromise p : byDate) {
                    if (onInvoice.signum() <= 0) break;
                    if (!covers(p, a.getInvoice())) continue;
                    BigDecimal take = onInvoice.min(need.get(p));
                    if (take.signum() <= 0) continue;
                    shares.get(p).add(new Share(pay, take));
                    need.put(p, need.get(p).subtract(take));
                    onInvoice = onInvoice.subtract(take);
                    pool = pool.subtract(take);
                }
            }
            for (PaymentPromise p : byDate) {
                if (pool.signum() <= 0) break;
                if (!p.getInvoices().isEmpty()) continue;
                BigDecimal take = pool.min(need.get(p));
                if (take.signum() <= 0) continue;
                shares.get(p).add(new Share(pay, take));
                need.put(p, need.get(p).subtract(take));
                pool = pool.subtract(take);
            }
        }
        return shares;
    }

    private static boolean counts(Payment payment, PaymentPromise promise) {
        return promise.getCreatedAt() == null || payment.getPaidAt() == null
                || !payment.getPaidAt().isBefore(promise.getCreatedAt());
    }

    private static boolean covers(PaymentPromise promise, Invoice invoice) {
        return invoice.getStatus() != InvoiceStatus.CANCELLED
                && promise.getInvoices().stream().anyMatch(i -> i.getId().equals(invoice.getId()));
    }

    private void relink(PaymentPromise promise, List<Share> shares, List<Payment> payments) {
        Set<Payment> links = new LinkedHashSet<>();
        shares.forEach(s -> links.add(s.payment()));
        for (Payment pay : payments) {
            if (counts(pay, promise)
                    && pay.getAllocations().stream().anyMatch(a -> covers(promise, a.getInvoice()))) {
                links.add(pay);
            }
        }
        promise.getPayments().retainAll(links);
        promise.getPayments().addAll(links);
    }

    private Fulfilment fulfilment(PaymentPromise promise, List<Share> shares) {
        BigDecimal onTime = BigDecimal.ZERO;
        BigDecimal late = BigDecimal.ZERO;
        for (Share s : shares) {
            if (isLateFor(s.payment(), promise)) {
                late = late.add(s.amount());
            } else {
                onTime = onTime.add(s.amount());
            }
        }
        boolean paidLate = promise.getPayments().stream().anyMatch(p -> isLateFor(p, promise));
        return new Fulfilment(onTime, late, paidLate);
    }

    private boolean isLateFor(Payment payment, PaymentPromise promise) {
        return payment.getPaidAt() != null && payment.getPaidAt().isAfter(endOfPromisedDate(promise));
    }

    private Instant endOfPromisedDate(PaymentPromise promise) {
        return promise.getPromisedDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private List<Invoice> liveInvoices(PaymentPromise promise) {
        return promise.getInvoices().stream()
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .toList();
    }

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

    @Transactional(readOnly = true)
    public Set<Long> invoicesToPayFirst(List<Long> promiseIds, Long customerId, Set<Long> chosenInvoiceIds) {
        Set<Long> first = new LinkedHashSet<>();
        if (promiseIds == null) return first;
        for (Long promiseId : promiseIds) {
            PaymentPromise promise = promiseRepository.findById(promiseId)
                    .orElseThrow(() -> new NotFoundException("Payment promise not found: " + promiseId));
            if (!promise.getCustomer().getId().equals(customerId)) {
                throw new BadRequestException("Promise " + promiseId + " belongs to a different customer");
            }
            if (promise.getStatus() == PromiseStatus.CANCELLED) {
                throw new BadRequestException("Promise " + promiseId + " has been cancelled");
            }
            Set<Long> invoices = liveInvoices(promise).stream().map(Invoice::getId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!chosenInvoiceIds.isEmpty() && !invoices.isEmpty()
                    && Collections.disjoint(invoices, chosenInvoiceIds)) {
                throw new BadRequestException("Promise " + promiseId + " covers none of the chosen "
                        + "invoices, so this payment would not count towards it");
            }
            first.addAll(invoices);
        }
        return first;
    }

    @Transactional
    public void reevaluateForCustomer(Long customerId) {
        if (customerId == null) return;
        evaluateCustomer(customerId, true, RECOMPUTED).forEach(promiseRepository::save);
    }

    public record RecomputeChange(Long promiseId, Long customerId, String customerName,
                                  BigDecimal amount, LocalDate promisedDate, boolean overridden,
                                  PromiseStatus statusBefore, PromiseStatus statusAfter,
                                  BigDecimal fulfilledBefore, BigDecimal fulfilledAfter) {}

    @Transactional
    public List<RecomputeChange> recomputeAll(boolean apply) {
        List<RecomputeChange> changes = new ArrayList<>();
        for (Long customerId : promiseRepository.findCustomerIdsWithLivePromises()) {
            List<PaymentPromise> live = promiseRepository.findLiveByCustomer(customerId);
            Map<PaymentPromise, PromiseStatus> statusBefore = new IdentityHashMap<>();
            Map<PaymentPromise, BigDecimal> fulfilledBefore = new IdentityHashMap<>();
            for (PaymentPromise p : live) {
                statusBefore.put(p, p.getStatus());
                fulfilledBefore.put(p, p.getFulfilledAmount());
            }
            evaluateCustomer(customerId, false, "Recomputed: each payment now counts once across promises")
                    .forEach(promiseRepository::save);
            for (PaymentPromise p : live) {
                boolean statusMoved = p.getStatus() != statusBefore.get(p);
                boolean amountMoved = p.getFulfilledAmount().compareTo(fulfilledBefore.get(p)) != 0;
                if (statusMoved || amountMoved) {
                    changes.add(new RecomputeChange(p.getId(), customerId, p.getCustomer().getName(),
                            p.getAmount(), p.getPromisedDate(), p.isStatusOverridden(),
                            statusBefore.get(p), p.getStatus(), fulfilledBefore.get(p), p.getFulfilledAmount()));
                }
            }
        }
        if (!apply) TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return changes;
    }

    @Transactional
    public void reevaluateForInvoice(Long invoiceId) {
        invoiceRepository.findById(invoiceId)
                .ifPresent(inv -> reevaluateForCustomer(inv.getCustomer().getId()));
    }

    @Transactional
    public int sweepOverdue() {
        List<PaymentPromise> overdue = promiseRepository.findOverdueOpen(InvoiceDates.today());
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

    @Transactional(readOnly = true)
    public PaymentPromise get(Long id) {
        PaymentPromise promise = promiseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's promise answers exactly as a missing one does, which is what the
        // book check below already does for a POC outside their book (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(promise.getCustomer().getId())) {
            throw new NotFoundException("Payment promise not found");
        }
        if (!queryExecutor.inScope(PaymentPromise.class, TableSchemas.PROMISES, id,
                scopeResolver.forPromises().predicates())) {
            throw new NotFoundException("Payment promise not found");
        }
        return promise;
    }

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
        boolean showStaff = !currentUser.isCustomer();
        return new PromiseDtos.PromiseDto(
                p.getId(), p.getCustomer().getId(), p.getCustomer().getName(),
                p.getAmount(), p.getFulfilledAmount(), p.getRemainingAmount(),
                p.getPromisedDate(), p.getStatus(), p.isStatusOverridden(),
                p.getOverrideReason(), showStaff ? p.getOverriddenByUserId() : null, p.getOverriddenAt(),
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
                showStaff ? p.getCreatedByUserId() : null, p.getCreatedAt(), p.getUpdatedAt());
    }

    private User resolveCollectionPoc(Long requested, Long customerId) {
        if (requested != null) {
            return pocService.requireAssignable(requested, PocType.COLLECTION);
        }
        return pocService.defaultAssignee(customerId, PocType.COLLECTION)
                .orElseThrow(() -> new BadRequestException(
                        "A Collection POC is required — this customer has no active Collection POC, "
                                + "so pick one explicitly"));
    }

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
                // The figure reads as it does on the promise page, the list and the CSV, rather
                // than as a bare number the reader has to recognise as money (PPD-06).
                promise.getCustomer().getName() + " promised "
                        + Money.format(promise.getAmount()) + " by " + promise.getPromisedDate()
                        + " and it has not been paid.",
                "/promises/" + promise.getId());
        promise.setBrokenNotifiedAt(Instant.now());
    }

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
