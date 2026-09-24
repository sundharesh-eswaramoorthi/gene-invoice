package com.geneinvoice.promise;

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
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceHistoryRepository;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentHistoryRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentStatus;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionPlacements;
import com.geneinvoice.region.RegionScope;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // Which invoices and payments a promise covered THEN, and how those records stood then.
    // The link mirrors carry the ids; the two entity mirrors carry the figures beside them
    // that PromiseInvoiceDto and PromisePaymentDto render (B3).
    private final PromiseInvoiceHistoryRepository promiseInvoiceHistoryRepository;
    private final PromisePaymentHistoryRepository promisePaymentHistoryRepository;
    private final InvoiceHistoryRepository invoiceHistoryRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
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
    // Which promises have a change waiting on them. The repository and not ApprovalService: one
    // query for a whole page, and the other direction would be a Spring cycle because
    // ApprovalService replays this service (B2).
    private final PendingChangeRepository pendingChangeRepository;

    @Transactional
    public PromiseDtos.PromiseDto create(PromiseDtos.CreatePromiseRequest req) {
        return createAs(currentUser.require().getId(), req);
    }

    /**
     * Creating a promise on behalf of a named actor. The only difference from {@link
     * #create(PromiseDtos.CreatePromiseRequest)} is where the actor comes from: a rule runs on a
     * daemon thread with no SecurityContext at all, and the person accountable for the row is then
     * the rule's author, passed in here rather than read from the request (A5).
     *
     * <p>Every guard and every approval gate on creating a promise belongs in THIS body and never
     * in the one-line delegate above: a check on the wrapper is a check the automated path walks
     * straight past (A5, B1, B2 INTEGRATION).
     */
    @Transactional
    public PromiseDtos.PromiseDto createAs(Long actorUserId, PromiseDtos.CreatePromiseRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // The guard is INSIDE this body and not on create(): the delegate is a self-invocation, so
        // a check placed there is one every automated caller of createAs walks straight past. This
        // was the third create path with no scope check at all (B1, A5 INTEGRATION).
        regionAccess.requireManage(customer.getRegion().getId());
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        if (req.promisedDate() == null) {
            throw new BadRequestException("Promised date is required");
        }
        // In THIS body and not on create(): the delegate is a self-invocation, so a gate placed
        // there is one every automated caller of createAs walks straight past (B2, A5 INTEGRATION).
        approvalGate.check(ApprovalGate.Proposal.creating(
                PendingAction.PROMISE_CREATE, customer.getId(), req.amount(), req,
                "Record a promise of " + Money.format(req.amount()) + " from " + customer.getName()
                        + " by " + req.promisedDate()));

        User poc = resolveCollectionPoc(req.collectionPocUserId(), customer);
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
                .createdByUserId(actorUserId)
                .build();
        promise = promiseRepository.save(promise);

        auditService.record(ENTITY, promise.getId(), "PROMISE_CREATED", null, snapshot(promise),
                actorUserId, null, req.notes());

        evaluate(promise);
        return toDto(promiseRepository.save(promise));
    }

    @Transactional
    public PromiseDtos.PromiseDto update(Long id, PromiseDtos.UpdatePromiseRequest req) {
        PaymentPromise promise = get(id);
        // get() has already answered "may I reach this row" with 404; CHANGING it needs MANAGE
        // where the account lives (B1, AUTH-08, D-46).
        regionAccess.requireManage(promise.getCustomer().getRegion().getId());
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be edited");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        // Zero exposure when the amount is not moving at all. This branch is load-bearing:
        // reassignCollectionPoc self-invokes this method with the promise's OWN amount (:203-204),
        // so without it every POC reassignment on a large promise would be held for approval and
        // the queue would fill with changes that move no money (B2).
        boolean sameAmount = req.amount().compareTo(promise.getAmount()) == 0;
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.PROMISE_UPDATE, id, promise.getCustomer().getId(),
                sameAmount ? BigDecimal.ZERO : promise.getAmount().max(req.amount()),
                req, snapshot(promise), promise.getVersion(),
                "Change the promise from " + promise.getCustomer().getName() + " of "
                        + Money.format(promise.getAmount()) + " to " + Money.format(req.amount())));
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
            User poc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION,
                    promise.getCustomer().getRegion().getId());
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
        // Withdrawing a promise is a write in the account's branch (B1).
        regionAccess.requireManage(promise.getCustomer().getRegion().getId());
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("Promise is already cancelled");
        }
        // The whole commitment leaves the book, not just the unfulfilled part: cancel sets
        // fulfilledAmount to ZERO (:226), so the exposure is the promise's full amount and it is
        // read off the record, because the request carries none (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.PROMISE_CANCEL, id, promise.getCustomer().getId(),
                promise.getAmount(), new ApprovalDtos.ReasonOnly(reason), snapshot(promise),
                promise.getVersion(),
                "Withdraw the promise of " + Money.format(promise.getAmount()) + " from "
                        + promise.getCustomer().getName()));
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
        // Taking charge of a promise's status by hand is a write in the account's branch (B1).
        regionAccess.requireManage(promise.getCustomer().getRegion().getId());
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be overridden");
        }
        // Declaring a promise kept by hand is how the engine's own answer is overruled, so the
        // figure at stake is the whole promise even though the request names only a status (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.PROMISE_OVERRIDE, id, promise.getCustomer().getId(),
                promise.getAmount(), new PromiseDtos.OverrideStatusRequest(status, reason),
                snapshot(promise), promise.getVersion(),
                "Mark the promise of " + Money.format(promise.getAmount()) + " from "
                        + promise.getCustomer().getName() + " as " + status));
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
        // Undoing an override is the same act as making one, and PUT and DELETE on one endpoint
        // cannot be gated differently (B1).
        regionAccess.requireManage(promise.getCustomer().getRegion().getId());
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be changed");
        }
        // not gated (B2): clearing an override hands the status back to the engine, which
        // recomputes it from payments already on the book. The only hand-made figure here is the
        // one being REMOVED, and putting it there was gated.
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
        // Deciding whether a promise is now broken is a write, so it reads the wall clock and
        // never the date a reader asked to see the world as of (B3).
        LocalDate today = InvoiceDates.todayForWrite();
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

    // not gated (B2), and a NAMED HOLE rather than an oversight: this spans every customer in
    // every region, so it has no single region and therefore no threshold to measure against. It
    // stays behind the plain company-wide PROMISE_OVERRIDE privilege, and the figures it moves are
    // engine-derived from payments that are already on the book.
    @Transactional
    public List<RecomputeChange> recomputeAll(boolean apply) {
        List<RecomputeChange> changes = new ArrayList<>();
        // findCustomerIdsWithLivePromises is a global sweep that knows nothing about who is asking,
        // so its id list is intersected with the accounts this caller may actually see: the same
        // funnel every list goes through, which now carries the region axis. Like every other bulk
        // id set in the application it truncates at BULK_ID_LIMIT, which is the documented shape
        // rather than a new limit (B1).
        Set<Long> visible = Set.copyOf(queryExecutor.ids(Customer.class, TableSchemas.CUSTOMERS,
                TableQuery.parseUnpaged(TableSchemas.CUSTOMERS, null, List.of()),
                List.of(), TableQueryExecutor.BULK_ID_LIMIT));
        for (Long customerId : promiseRepository.findCustomerIdsWithLivePromises()) {
            if (!visible.contains(customerId)) continue;
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

    // not gated (B2): the sweep has no principal at all — nobody saved anything — and it writes
    // only the status the engine already derives from payments on the book.
    @Transactional
    public int sweepOverdue() {
        // The sweep writes statuses, so it is dated by the wall clock, never by an as-of date
        // left behind by whatever request happens to be in flight (B3).
        List<PaymentPromise> overdue = promiseRepository.findOverdueOpen(InvoiceDates.todayForWrite());
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
        if (AsOfContext.isActive()) return detail(id);
        PaymentPromise promise = get(id);
        // The sentinel column answers this in one indexed lookup; a decided change releases its
        // key, so only a change still waiting can be found here (B2).
        PromiseDtos.PromiseDto live = toDto(promise, pendingChangeRepository.existsByPendingKey(
                PendingChange.keyOf(PendingTargetType.PROMISE, promise.getId())));
        // The one read edge that does not go through toDtos, redacted for the same reason it is
        // (B1, AUTH-08).
        return scopeResolver.canSeeRegion() ? live : live.withoutRegion();
    }

    /**
     * ONE PROMISE AS IT STOOD ON THE DATE ASKED ABOUT — and 404, never 403, when it did not exist
     * then (B3). The InvoiceService.detail shape: the same mirror, the same executor, the same
     * scope list and an {@code id:eq:} filter, so the book, the region axis and a customer login's
     * own-account pin all come from one place rather than from a copy of {@link #get}'s checks.
     */
    private PromiseDtos.PromiseDto detail(Long id) {
        Long callerCustomer = currentUser.customerIdOrNull();
        AsOfSource<PromiseView> source = promiseSource();
        List<? extends PromiseView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()
                || (callerCustomer != null && !callerCustomer.equals(rows.get(0).getCustomerId()))) {
            throw new NotFoundException("Payment promise not found");
        }
        placeRegions(rows);
        markDrift();
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.PROMISE, List.of(id));
        return toDtos(rows, held).get(0);
    }

    /**
     * WHERE A PROMISE LIST READS FROM (B3). The InvoiceService.invoiceSource() shape exactly: live
     * it is the payment_promises table, its live schema and the POC book; under {@code ?asOf} it
     * is the interval mirror, its as-of twin, and the SAME book, because the book predicate names
     * the flat {@code collectionPocUserId} both roots carry.
     *
     * <p>{@code AsOf.at(T)} LEADS THE SCOPE LIST: the twin carries the interval clause only inside
     * its correlated subqueries — the region ledger, the outstanding approvals, and the two LINK
     * mirrors behind the invoiceId and paymentId filters — and nothing filters the root.
     *
     * <p>IT ADDS NO REGION PREDICATE; the axis is injected once by the executor (blueprint
     * conflict 1, B1, B3). {@code customer} drops out of the fetch list because a mirror has no
     * Customer to walk, while {@code collectionPoc} stays: the person is not mirrored and renders
     * as they are today (clause a.3).
     */
    public AsOfSource<PromiseView> promiseSource() {
        ScopeResolver.Scope book = scopeResolver.forPromises();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(PaymentPromise.class, TableSchemas.PROMISES, book.predicates(),
                    book.lockedFilters(), List.of("customer", "collectionPoc"));
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(PromiseHistory.class, HistorySchemas.PROMISES,
                List.copyOf(scope), List.copyOf(locked), List.of("collectionPoc"));
    }

    /** The branch an as-of row cannot answer for itself; filled from R7's ledger (B3, B1). */
    private void placeRegions(List<? extends PromiseView> rows) {
        if (!AsOfContext.isActive()) return;
        List<PromiseHistory> mirrors = rows.stream()
                .filter(PromiseHistory.class::isInstance).map(PromiseHistory.class::cast).toList();
        if (mirrors.isEmpty()) return;
        Map<Long, RegionPlacements.Placement> placements = regionPlacements.at(
                mirrors.stream().map(PromiseHistory::getCustomerId).filter(Objects::nonNull).toList(),
                AsOfContext.date());
        for (PromiseHistory row : mirrors) {
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
        historyDrift.markIfDrifted(PromiseHistory.class, AsOfContext.instant());
    }

    /**
     * THE TWO LINK COLLECTIONS OF A WHOLE PAGE, BATCHED (B3).
     *
     * <p>A promise carries what it covers on every LIST row. The live path walks two
     * {@code @ManyToMany} collections off the loaded entity; the as-of path cannot, because the
     * link mirrors are flat and a mirror never walks to a live row of a mirrored entity. So a page
     * costs four queries here — the two link mirrors in force at T, then the invoice and payment
     * versions those links name — rather than two collection loads per row.
     *
     * <p>A LINK WHOSE OTHER END HAS NO VERSION IN FORCE AT T still appears, carrying its id and
     * nulls for the figures. That is the "not asked" convention rather than a silent drop: the
     * promise did cover that invoice, and dropping the row would understate its coverage.
     */
    private List<PromiseDtos.PromiseDto> toDtos(List<? extends PromiseView> promises, Set<Long> held) {
        // WHICH BRANCH is internal: a customer login gets B1's two slots empty, the same two
        // TableSchema.visibleTo drops from their column list, so the payload and the schema agree
        // (B1, AUTH-08).
        boolean region = scopeResolver.canSeeRegion();
        if (!AsOfContext.isActive()) {
            return promises.stream()
                    .map(p -> toDto((PaymentPromise) p, held.contains(p.getId())))
                    .map(row -> region ? row : row.withoutRegion()).toList();
        }
        Instant at = AsOfContext.instant();
        List<Long> ids = promises.stream().map(PromiseView::getId).toList();

        Map<Long, List<Long>> invoiceLinks = new LinkedHashMap<>();
        Map<Long, List<Long>> paymentLinks = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (PromiseInvoiceHistory l : promiseInvoiceHistoryRepository.inForce(ids, at)) {
                invoiceLinks.computeIfAbsent(l.getId(), k -> new ArrayList<>()).add(l.getInvoiceId());
            }
            for (PromisePaymentHistory l : promisePaymentHistoryRepository.inForce(ids, at)) {
                paymentLinks.computeIfAbsent(l.getId(), k -> new ArrayList<>()).add(l.getPaymentId());
            }
        }
        Map<Long, InvoiceHistory> invoices = new LinkedHashMap<>();
        List<Long> invoiceIds = invoiceLinks.values().stream().flatMap(List::stream)
                .filter(Objects::nonNull).distinct().toList();
        if (!invoiceIds.isEmpty()) {
            for (InvoiceHistory i : invoiceHistoryRepository.inForce(invoiceIds, at)) {
                invoices.put(i.getId(), i);
            }
        }
        Map<Long, PaymentHistory> payments = new LinkedHashMap<>();
        List<Long> paymentIds = paymentLinks.values().stream().flatMap(List::stream)
                .filter(Objects::nonNull).distinct().toList();
        if (!paymentIds.isEmpty()) {
            for (PaymentHistory p : paymentHistoryRepository.inForce(paymentIds, at)) {
                payments.put(p.getId(), p);
            }
        }

        return promises.stream().map(p -> toDto(p,
                invoiceLinks.getOrDefault(p.getId(), List.<Long>of()).stream()
                        .map(invoiceId -> promiseInvoice(invoiceId, invoices.get(invoiceId)))
                        .sorted(Comparator.comparing(PromiseDtos.PromiseInvoiceDto::id))
                        .toList(),
                paymentLinks.getOrDefault(p.getId(), List.<Long>of()).stream()
                        .map(paymentId -> promisePayment(paymentId, payments.get(paymentId)))
                        .sorted(Comparator.comparing(PromiseDtos.PromisePaymentDto::id))
                        .toList(),
                held.contains(p.getId())))
                .map(row -> region ? row : row.withoutRegion()).toList();
    }

    private static PromiseDtos.PromiseInvoiceDto promiseInvoice(Long id, InvoiceHistory inv) {
        if (inv == null) return new PromiseDtos.PromiseInvoiceDto(id, null, null, null, null);
        return new PromiseDtos.PromiseInvoiceDto(inv.getId(), inv.getInvoiceNumber(),
                inv.getTotal(), inv.getBalance(), inv.getStatus().name());
    }

    private static PromiseDtos.PromisePaymentDto promisePayment(Long id, PaymentHistory pay) {
        if (pay == null) return new PromiseDtos.PromisePaymentDto(id, null, null, null, null);
        return new PromiseDtos.PromisePaymentDto(pay.getId(), pay.getAmount(), pay.getPaidAt(),
                pay.getMethod(), pay.getStatus().name());
    }

    // listForCustomer and listForInvoice are DELETED: both read past the book and past the region
    // predicate, and neither had a caller. The page() path above is the scoped way to ask the same
    // question, and a finder that answers "everything" is how the next endpoint written here would
    // be unscoped without anyone deciding that it should be (B1).

    @Transactional(readOnly = true)
    public PageResponse<PromiseDtos.PromiseDto> page(TableQuery query) {
        AsOfSource<PromiseView> source = promiseSource();
        var page = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch());
        placeRegions(page.content());
        markDrift();
        // One query for the whole page, and none at all for an empty one. Under an as-of date it
        // asks the decision LOG instead, inside openTargetIds, so this line does not move (B2, B3).
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.PROMISE,
                page.content().stream().map(PromiseView::getId).toList());
        return PageResponse.of(toDtos(page.content(), held),
                query, page.total(), source.locked(), regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<PromiseView> source = promiseSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /**
     * The rows behind an export. {@code ? extends PromiseView} and not {@code PaymentPromise},
     * because under {@code ?asOf} these are mirror rows and the caller renders them through the
     * view (B3).
     */
    @Transactional(readOnly = true)
    public List<? extends PromiseView> allMatching(TableQuery query) {
        AsOfSource<PromiseView> source = promiseSource();
        List<? extends PromiseView> rows = queryExecutor.run(source.type(), source.schema(),
                query, source.scope(), source.fetch()).content();
        placeRegions(rows);
        markDrift();
        return rows;
    }

    /** The DTOs behind an export: the page's own mapper, so the file and the list agree (B3). */
    @Transactional(readOnly = true)
    public List<PromiseDtos.PromiseDto> toDtos(List<? extends PromiseView> promises) {
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.PROMISE,
                promises.stream().map(PromiseView::getId).toList());
        return toDtos(promises, held);
    }

    @Transactional(readOnly = true)
    public PromiseDtos.PromiseSummaryDto tiles(TableQuery query) {
        AsOfSource<PromiseView> source = promiseSource();
        markDrift();
        // The thirteen selection lambdas are UNCHANGED: amount, status and fulfilledAmount all
        // resolve on the mirror under the same name and the same Java type, and the approval
        // EXISTS switched to the decision log inside ApprovalSchemas (B3).
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(), query,
                source.scope(), (root, q, cb) -> {
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
                            cb.coalesce(cb.sum(root.<BigDecimal>get("fulfilledAmount")), BigDecimal.ZERO),
                            // Inside the SAME aggregate, so the tile costs no extra round trip
                            // and can never disagree with the approvalPending filter chip (B2).
                            Aggregates.countWhen(cb, ApprovalSchemas.existsOpenPending(
                                    root, q, cb, PendingTargetType.PROMISE)));
                });
        return new PromiseDtos.PromiseSummaryDto(
                Aggregates.asLong(row[0]),
                Aggregates.asLong(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asMoney(row[4]),
                Aggregates.asLong(row[5]), Aggregates.asMoney(row[6]),
                Aggregates.asLong(row[7]), Aggregates.asMoney(row[8]),
                Aggregates.asLong(row[9]),
                Aggregates.asMoney(row[10]), Aggregates.asMoney(row[11]),
                Aggregates.asLong(row[12]));
    }

    /**
     * The snapshot shape: approvalPending is null, meaning "not asked". Every write path answers
     * through this overload, so what a POST returns does not vary with an unrelated pending row
     * and the signature every existing caller uses is unchanged (B2).
     */
    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto toDto(PaymentPromise p) {
        return toDto(p, null);
    }

    /** The read shape: the list and the single-record GET have the flag in hand and pass it (B2). */
    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto toDto(PaymentPromise p, Boolean approvalPending) {
        return toDto(p,
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
                approvalPending);
    }

    /**
     * The root-agnostic shape: everything flat comes off the view, and the two link collections a
     * mirror row cannot answer for itself are handed in. An as-of promise's invoices and payments
     * are the link-mirror rows in force on the date asked, so an as-of read passes its own two
     * lists rather than this mapper pretending the promise covered nothing (B3).
     */
    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto toDto(PromiseView p, List<PromiseDtos.PromiseInvoiceDto> invoices,
                                        List<PromiseDtos.PromisePaymentDto> payments,
                                        Boolean approvalPending) {
        boolean showPoc = scopeResolver.canSeePoc();
        boolean showStaff = !currentUser.isCustomer();
        return new PromiseDtos.PromiseDto(
                p.getId(), p.getCustomerId(), p.getCustomerName(),
                p.getAmount(), p.getFulfilledAmount(), p.getRemainingAmount(),
                p.getPromisedDate(), p.getStatus(), p.isStatusOverridden(),
                p.getOverrideReason(), showStaff ? p.getOverriddenByUserId() : null, p.getOverriddenAt(),
                showPoc ? PocDtos.PocUserDto.from(p.getCollectionPoc()) : null,
                p.getNotes(),
                invoices,
                payments,
                showStaff ? p.getCreatedByUserId() : null, p.getCreatedAt(), p.getUpdatedAt(),
                p.getRegionId(), p.getRegionName(),
                approvalPending);
    }

    private User resolveCollectionPoc(Long requested, Customer customer) {
        if (requested != null) {
            // The account's own branch: a Collection POC who cannot work there would be promised
            // money they cannot see (B1).
            return pocService.requireAssignable(requested, PocType.COLLECTION,
                    customer.getRegion().getId());
        }
        // The default assignee is a SEAT this account already holds, and a seat is already
        // region-checked when it is created and vacated when the account moves (B1).
        return pocService.defaultAssignee(customer.getId(), PocType.COLLECTION)
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
