package com.geneinvoice.promise;

import com.geneinvoice.approval.ApprovalOperation;
import com.geneinvoice.approval.Gated;
import com.geneinvoice.approval.MakerChecker;
import com.geneinvoice.approval.PendingChangeScope;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeStore;
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
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.history.Records;
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
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
    private final RecordAccess recordAccess;
    private final RegionAccessQueries regionAccess;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Records records;
    private final MakerChecker makerChecker;
    private final PendingChangeScope pendingChangeScope;
    private final AssigneeStore assigneeStore;

    @Transactional
    public PromiseDtos.PromiseDto create(PromiseDtos.CreatePromiseRequest req) {
        return createOrHold(req).appliedOrThrow();
    }

    /**
     * Makes the promise, or, above the region's PROMISE_CREATE limit, holds it for someone else to approve (B2).
     * The held request keeps the Collection POC it resolved to, so the approval makes the promise the maker saw,
     * and its co-assignees, who are assigned once it is made (§5.10.12); whoever calls tells them.
     */
    @Transactional
    public Outcome<PromiseDtos.PromiseDto> createOrHold(PromiseDtos.CreatePromiseRequest req) {
        // The customer first (F1): the promise takes its region, and a region move cannot interleave.
        Customer customer = customerRepository.findByIdForUpdate(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        ViewScopeHolder.requireLive();
        // Book-aware: outside the caller's book the customer is missing, and nothing is written (SC-12).
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, customer.getId(), Region.idOf(customer.getRegion()),
                "Customer not found");
        currentUser.requireIn(Privileges.PROMISE_MANAGE, Region.idOf(customer.getRegion()));
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        if (req.promisedDate() == null) {
            throw new BadRequestException("Promised date is required");
        }

        User poc = resolveCollectionPoc(req.collectionPocUserId(), customer.getId(), Region.idOf(customer.getRegion()));
        Set<Invoice> invoices = resolveInvoices(req.invoiceIds(), customer.getId(), Set.of());
        List<EmailToken> assignees = assigneeStore.validate(EmailEntityType.PROMISE, req.assignees(),
                EmailTargets.offeredRoles(EmailEntityType.PROMISE));
        Optional<PendingChange> held = makerChecker.gate(createGate(customer, poc, invoices, req, assignees));
        if (held.isPresent()) return Outcome.held(held.get());

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
                .region(customer.getRegion())
                .build();
        promise = promiseRepository.save(promise);
        assigneeStore.replace(AssignedTo.PROMISE, promise.getId(), assignees);

        auditService.record(ENTITY, promise.getId(), "PROMISE_CREATED", null, snapshot(promise),
                currentUser.require().getId(), null, req.notes());

        evaluate(promise);
        return Outcome.applied(toDto(promiseRepository.save(promise)));
    }

    private static Gated createGate(Customer customer, User poc, Set<Invoice> invoices,
                                    PromiseDtos.CreatePromiseRequest req, List<EmailToken> assignees) {
        Long pocId = poc == null ? null : poc.getId();
        List<Long> invoiceIds = invoices.stream().map(Invoice::getId).sorted().toList();
        PromiseDtos.CreatePromiseRequest payload = new PromiseDtos.CreatePromiseRequest(req.customerId(),
                req.amount(), req.promisedDate(), pocId, req.notes(), req.invoiceIds(),
                assignees.isEmpty() ? null : assignees);
        return new Gated(ApprovalOperation.PROMISE_CREATE, null, "New promise from " + customer.getName(), ENTITY,
                null, null, customer.getId(), customer.getName(), Region.idOf(customer.getRegion()), pocId,
                req.amount(), payload, null,
                new PromiseChangeApplier.PromiseView(req.amount(), req.promisedDate(), pocId, req.notes(), invoiceIds),
                "Promise of " + Money.format(req.amount()) + " by " + req.promisedDate() + " from "
                        + customer.getName(),
                false);
    }

    @Transactional
    public PromiseDtos.PromiseDto update(Long id, PromiseDtos.UpdatePromiseRequest req) {
        return updateOrHold(id, req).appliedOrThrow();
    }

    /**
     * Replaces the promise's terms. Promising less, or the same later, above the region's PROMISE_UPDATE limit
     * is held for someone else to approve and the promise is left as it is (B2).
     */
    @Transactional
    public Outcome<PromiseDtos.PromiseDto> updateOrHold(Long id, PromiseDtos.UpdatePromiseRequest req) {
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_MANAGE);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be edited");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BadRequestException("Promised amount must be greater than zero");
        }
        // Checked before the gate, so a held change is one that could be made as it stands.
        Long previousPocId = promise.getCollectionPoc() == null ? null : promise.getCollectionPoc().getId();
        User newPoc = null;
        if (req.collectionPocUserId() != null && !req.collectionPocUserId().equals(previousPocId)) {
            // Moving a promise's Collection POC is the same act as moving a payment's, and needs
            // the same privilege: without this, POC_ASSIGN guarded one list and not the other
            // (PPD-05).
            if (!currentUser.canAssignPocIn(Region.idOf(promise.getRegion()))) {
                throw new BadRequestException("You may not change the Collection POC");
            }
            newPoc = pocService.requireAssignable(req.collectionPocUserId(), PocType.COLLECTION,
                    Region.idOf(promise.getRegion()));
        }
        Set<Invoice> newInvoices = null;
        if (req.invoiceIds() != null) {
            Set<Long> linked = promise.getInvoices().stream().map(Invoice::getId)
                    .collect(java.util.stream.Collectors.toSet());
            newInvoices = resolveInvoices(req.invoiceIds(), promise.getCustomer().getId(), linked);
        }
        Optional<PendingChange> held = makerChecker.gate(updateGate(promise, req, newInvoices));
        if (held.isPresent()) return Outcome.held(held.get());
        Object before = snapshot(promise);

        promise.setAmount(req.amount());
        promise.setPromisedDate(req.promisedDate());
        promise.setNotes(req.notes());
        if (newPoc != null) {
            promise.setCollectionPoc(newPoc);
            pocService.notifyAssignee(newPoc, PocType.COLLECTION,
                    "a payment promise from " + promise.getCustomer().getName(),
                    "/promises/" + promise.getId(), Region.idOf(promise.getRegion()));
        }
        if (newInvoices != null) promise.setInvoices(newInvoices);

        evaluate(promise);
        PaymentPromise saved = promiseRepository.save(promise);
        auditService.record(ENTITY, id, "PROMISE_UPDATED", before, snapshot(saved),
                currentUser.require().getId(), null, null);
        return Outcome.applied(toDto(saved));
    }

    /**
     * A change of terms measures what it gives away: the amount no longer promised, or, when the date moves
     * later, the whole amount now owed later. The full PUT is kept; an approval re-bases it on the promise as it
     * is then (§5.11.6).
     */
    private static Gated updateGate(PaymentPromise promise, PromiseDtos.UpdatePromiseRequest req,
                                    Set<Invoice> newInvoices) {
        PromiseChangeApplier.PromiseView base = PromiseChangeApplier.PromiseView.of(promise);
        BigDecimal amount = req.amount().subtract(promise.getAmount()).abs();
        if (req.promisedDate() != null && promise.getPromisedDate() != null
                && req.promisedDate().isAfter(promise.getPromisedDate())) {
            amount = amount.max(req.amount());
        }
        PromiseChangeApplier.PromiseView proposed = new PromiseChangeApplier.PromiseView(req.amount(),
                req.promisedDate(),
                req.collectionPocUserId() != null ? req.collectionPocUserId() : base.collectionPocUserId(),
                req.notes(),
                newInvoices != null ? newInvoices.stream().map(Invoice::getId).sorted().toList() : base.invoiceIds());
        return new Gated(ApprovalOperation.PROMISE_UPDATE, promise.getId(), label(promise), ENTITY, promise.getId(),
                null, promise.getCustomer().getId(), promise.getCustomer().getName(),
                Region.idOf(promise.getRegion()), base.collectionPocUserId(), amount, req, base, proposed,
                "Change " + label(promise) + ": " + Money.format(promise.getAmount()) + " by "
                        + promise.getPromisedDate() + " to " + Money.format(req.amount()) + " by "
                        + req.promisedDate(),
                false);
    }

    /** How a promise is named to an approver: "Promise #12 from Acme Ltd". */
    private static String label(PaymentPromise promise) {
        return "Promise #" + promise.getId() + " from " + promise.getCustomer().getName();
    }

    /**
     * Makes the promise's co-assignees exactly {@code tokens} (§5.10.12): PROMISE_MANAGE in its region, customer then
     * promise locked. The caller has already made sure each person named can see the promise; this returns the
     * tokens added, for the caller to tell.
     */
    @Transactional
    public List<EmailToken> setAssignees(Long id, List<EmailToken> tokens) {
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_MANAGE);
        List<EmailToken> wanted = assigneeStore.validate(EmailEntityType.PROMISE, tokens,
                EmailTargets.offeredRoles(EmailEntityType.PROMISE));
        List<EmailToken> before = assigneeStore.list(AssignedTo.PROMISE, id);
        if (assigneeKeys(before).equals(assigneeKeys(wanted))) return List.of();
        List<EmailToken> added = assigneeStore.replace(AssignedTo.PROMISE, id, wanted);
        auditService.record(ENTITY, id, "PROMISE_ASSIGNEES_CHANGED", assigneesOf(before), assigneesOf(wanted),
                WriteOrigins.actor(currentUser), null, null, Region.idOf(promise.getRegion()));
        return added;
    }

    private static List<String> assigneeKeys(List<EmailToken> tokens) {
        return tokens.stream().map(AssigneeStore::key).sorted().toList();
    }

    private static Map<String, Object> assigneesOf(List<EmailToken> tokens) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignees", tokens.stream().map(AssigneeStore::key).toList());
        return out;
    }

    @Transactional
    public PromiseDtos.PromiseDto reassignCollectionPoc(Long id, Long userId) {
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_MANAGE);
        return update(id, new PromiseDtos.UpdatePromiseRequest(promise.getAmount(),
                promise.getPromisedDate(), userId, promise.getNotes(), null));
    }

    @Transactional
    public PromiseDtos.PromiseDto cancel(Long id, String reason) {
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_MANAGE);
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
        return overrideOrHold(id, status, reason).appliedOrThrow();
    }

    /**
     * Sets the promise's status by hand. Calling it kept (or anything but broken) while money it promised is
     * still unpaid, above the region's PROMISE_OVERRIDE limit, is held for someone else to approve (B2).
     */
    @Transactional
    public Outcome<PromiseDtos.PromiseDto> overrideOrHold(Long id, PromiseStatus status, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("An override requires a reason");
        }
        if (status == PromiseStatus.CANCELLED) {
            throw new BadRequestException("Use cancel to withdraw a promise");
        }
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_OVERRIDE);
        if (promise.getStatus() == PromiseStatus.CANCELLED) {
            throw new BadRequestException("A cancelled promise cannot be overridden");
        }
        Object before = snapshot(promise);
        Optional<PendingChange> held = makerChecker.gate(overrideGate(promise, status, reason));
        if (held.isPresent()) return Outcome.held(held.get());

        promise.setStatus(status);
        promise.setStatusOverridden(true);
        promise.setOverrideReason(reason);
        promise.setOverriddenByUserId(currentUser.require().getId());
        promise.setOverriddenAt(clock.instant());
        if (status != PromiseStatus.BROKEN) promise.setBrokenNotifiedAt(null);
        PaymentPromise saved = promiseRepository.save(promise);

        auditService.record(ENTITY, id, "PROMISE_STATUS_OVERRIDDEN", before, snapshot(saved),
                currentUser.require().getId(), null, reason);
        if (status == PromiseStatus.BROKEN) notifyBroken(saved);
        return Outcome.applied(toDto(saved));
    }

    /** An override measures the promised money still unpaid that it stops chasing; calling it broken is free. */
    private static Gated overrideGate(PaymentPromise promise, PromiseStatus status, String reason) {
        BigDecimal fulfilled = promise.getFulfilledAmount() == null ? BigDecimal.ZERO : promise.getFulfilledAmount();
        BigDecimal amount = status == PromiseStatus.BROKEN
                ? BigDecimal.ZERO
                : promise.getAmount().subtract(fulfilled).max(BigDecimal.ZERO);
        return new Gated(ApprovalOperation.PROMISE_OVERRIDE, promise.getId(), label(promise), ENTITY,
                promise.getId(), null, promise.getCustomer().getId(), promise.getCustomer().getName(),
                Region.idOf(promise.getRegion()),
                promise.getCollectionPoc() == null ? null : promise.getCollectionPoc().getId(), amount,
                new PromiseDtos.OverrideStatusRequest(status, reason), PromiseChangeApplier.OverrideView.of(promise),
                new PromiseChangeApplier.OverrideView(status, true, reason),
                "Mark " + label(promise) + " " + status + " with " + Money.format(amount) + " unpaid",
                false);
    }

    @Transactional
    public PromiseDtos.PromiseDto clearOverride(Long id) {
        PaymentPromise promise = getForWrite(id, Privileges.PROMISE_OVERRIDE);
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

    /**
     * Re-reads the customer's live promises under the customer's lock, so no path saves a promise without it
     * (SC-7). Re-taking the lock a caller already holds costs a query and changes nothing.
     */
    @Transactional
    public void reevaluateForCustomer(Long customerId) {
        if (customerId == null) return;
        if (customerRepository.findByIdForUpdate(customerId).isEmpty()) return;
        evaluateCustomer(customerId, true, RECOMPUTED).forEach(promiseRepository::save);
    }

    public record RecomputeChange(Long promiseId, Long customerId, String customerName,
                                  BigDecimal amount, LocalDate promisedDate, boolean overridden,
                                  PromiseStatus statusBefore, PromiseStatus statusAfter,
                                  BigDecimal fulfilledBefore, BigDecimal fulfilledAfter) {}

    /**
     * Only the promises the caller could override by hand: in the regions they may override promises in,
     * narrowed to those the request asks about, and within those their book where that is all they see (B1,
     * SC-12); a customer login only its own. A customer's promises are evaluated together, so a customer with
     * any live promise out of reach is left alone. Each customer is locked, in ascending id, before its
     * promises are re-read.
     */
    @Transactional
    public List<RecomputeChange> recomputeAll(boolean apply) {
        List<RecomputeChange> changes = new ArrayList<>();
        Long own = currentUser.customerIdOrNull();
        Set<Long> overridable = currentUser.regionsWith(Privileges.PROMISE_OVERRIDE);
        // A customer login has no choice of region to narrow (D17).
        Set<Long> regions = own != null ? overridable : ViewScopeHolder.current().narrow(overridable);
        if (regions.isEmpty()) return changes;
        Long me = currentUser.require().getId();
        for (Long customerId : recomputableCustomers(regions, own, me)) {
            if (customerRepository.findByIdForUpdate(customerId).isEmpty()) continue;
            List<PaymentPromise> live = promiseRepository.findLiveByCustomer(customerId);
            if (!live.stream().allMatch(p -> mayRecompute(p, regions, own, me))) continue;
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

    /** Ascending: the customers in full regions, and in book regions those with a live promise of the caller's. */
    private Set<Long> recomputableCustomers(Set<Long> regions, Long own, Long me) {
        if (own != null) return Set.of(own);
        List<Long> full = new ArrayList<>();
        List<Long> book = new ArrayList<>();
        for (Long region : regions) {
            switch (scopeResolver.tier(ScopeResolver.Kind.PROMISE, ScopeResolver.Access.VIEW, region,
                    Privileges.PROMISE_OVERRIDE)) {
                case FULL -> full.add(region);
                case BOOK -> book.add(region);
                case NONE -> { }
            }
        }
        Set<Long> customers = new TreeSet<>();
        if (!full.isEmpty()) customers.addAll(promiseRepository.findCustomerIdsWithLivePromisesInRegions(full));
        if (!book.isEmpty()) customers.addAll(promiseRepository.findCustomerIdsWithLivePromisesOf(book, me));
        return customers;
    }

    /** Whether the caller could override this promise by hand, as {@link #getForWrite} would allow. */
    private boolean mayRecompute(PaymentPromise p, Set<Long> regions, Long own, Long me) {
        Long region = Region.idOf(p.getRegion());
        if (region == null || !regions.contains(region)) return false;
        if (own != null) return own.equals(p.getCustomer().getId());
        return switch (scopeResolver.tier(ScopeResolver.Kind.PROMISE, ScopeResolver.Access.VIEW, region,
                Privileges.PROMISE_OVERRIDE)) {
            case FULL -> true;
            case BOOK -> p.getCollectionPoc() != null && me.equals(p.getCollectionPoc().getId());
            case NONE -> false;
        };
    }

    @Transactional
    public void reevaluateForInvoice(Long invoiceId) {
        invoiceRepository.findById(invoiceId)
                .ifPresent(inv -> reevaluateForCustomer(inv.getCustomer().getId()));
    }

    /**
     * Each customer with an overdue open promise is locked, in ascending id, before its promises are read and
     * saved (SC-7); every promise the evaluation changed is counted once. Each customer is its own short
     * transaction, so the sweep holds one customer's lock at a time rather than every one until it ends.
     */
    public int sweepOverdue() {
        int changed = 0;
        for (Long customerId : promiseRepository.findCustomerIdsWithOverdueOpen(InvoiceDates.today())) {
            Integer swept = transactions.execute(status -> {
                if (customerRepository.findByIdForUpdate(customerId).isEmpty()) return 0;
                List<PaymentPromise> moved = evaluateCustomer(customerId, true, RECOMPUTED);
                moved.forEach(promiseRepository::save);
                return moved.size();
            });
            changed += swept == null ? 0 : swept;
        }
        if (changed > 0) log.info("Promise sweep marked {} promise(s) broken or updated", changed);
        return changed;
    }

    /** The promise, or as of a date the promise as it was then (not found if it did not exist yet). */
    @Transactional(readOnly = true)
    public PaymentPromise get(Long id) {
        PaymentPromise promise = records.find(PaymentPromise.class, id)
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        // Another customer's promise answers exactly as a missing one does, which is what the
        // book check below already does for a POC outside their book (AUTH-08).
        if (callerCustomer != null && !callerCustomer.equals(promise.getCustomer().getId())) {
            throw new NotFoundException("Payment promise not found");
        }
        scopeResolver.requireVisible(ScopeResolver.Kind.PROMISE, id, Region.idOf(promise.getRegion()),
                "Payment promise not found");
        return promise;
    }

    /**
     * A promise to change, locked customer first (§5.11.5, SC-7): its customer id is read without a lock, then
     * the customer row is locked, then the promise, and only then is its region read, so a concurrent region
     * move has either committed or waits. One the caller cannot see is missing (404); lacking the privilege in
     * its region is a 403.
     */
    private PaymentPromise getForWrite(Long id, String privilege) {
        Long customerId = promiseRepository.customerIdOf(id)
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        customerRepository.findByIdForUpdate(customerId);
        PaymentPromise promise = promiseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Payment promise not found"));
        scopeResolver.requireVisible(ScopeResolver.Kind.PROMISE, id, Region.idOf(promise.getRegion()),
                "Payment promise not found");
        ViewScopeHolder.requireLive();
        currentUser.requireIn(privilege, Region.idOf(promise.getRegion()));
        return promise;
    }

    @Transactional(readOnly = true)
    public PromiseDtos.PromiseDto dto(Long id) {
        return toDto(get(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PromiseDtos.PromiseDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forPromises();
        var page = queryExecutor.run(PaymentPromise.class, TableSchemas.PROMISES, query,
                scope.predicates(), List.of("customer", "collectionPoc", "region"));
        Set<Long> pending = pendingChangeScope.pendingOn(ENTITY,
                page.content().stream().map(PaymentPromise::getId).toList());
        return PageResponse.of(page.content().stream()
                        .map(p -> toDto(p).withPendingApproval(pending.contains(p.getId())))
                        .toList(),
                query, page.total(), scope);
    }

    /** The ids a bulk action or export may touch: VIEW for an export, MANAGE (and any extras) for a write. */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit, ScopeResolver.Access access, String... alsoRequired) {
        return queryExecutor.ids(PaymentPromise.class, TableSchemas.PROMISES, query,
                scopeResolver.scope(ScopeResolver.Kind.PROMISE, access, ScopeResolver.Reach.SELECTED, alsoRequired)
                        .predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<PaymentPromise> allMatching(TableQuery query) {
        return queryExecutor.run(PaymentPromise.class, TableSchemas.PROMISES, query,
                scopeResolver.forPromises().predicates(), List.of("customer", "collectionPoc", "region")).content();
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
                showStaff ? p.getCreatedByUserId() : null, p.getCreatedAt(), p.getUpdatedAt(),
                Region.idOf(p.getRegion()), Region.codeOf(p.getRegion()), Region.nameOf(p.getRegion()),
                recordAccess.canManage(ScopeResolver.Kind.PROMISE, Region.idOf(p.getRegion())),
                recordAccess.can(Privileges.PROMISE_OVERRIDE, Region.idOf(p.getRegion())), null, null);
    }

    private User resolveCollectionPoc(Long requested, Long customerId, Long regionId) {
        if (requested != null) {
            return pocService.requireAssignable(requested, PocType.COLLECTION, regionId);
        }
        // The customer's holders are only those with access to its region, so the default is too.
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

    /**
     * The promise's Collection POC hears that it broke, provided they can still reach its region; a POC left
     * without access (a region move, a grant taken away) is not sent a link they cannot open, and the region's
     * promise managers are told instead (SC-17).
     */
    private void notifyBroken(PaymentPromise promise) {
        if (promise.getBrokenNotifiedAt() != null) return;
        if (promise.getCollectionPoc() == null) return;
        Long regionId = Region.idOf(promise.getRegion());
        Long pocId = promise.getCollectionPoc().getId();
        String title = "Promise broken — " + promise.getCustomer().getName();
        // The figure reads as it does on the promise page, the list and the CSV, rather than as a bare number
        // the reader has to recognise as money (PPD-06).
        String message = promise.getCustomer().getName() + " promised "
                + Money.format(promise.getAmount()) + " by " + promise.getPromisedDate()
                + " and it has not been paid.";
        String link = "/promises/" + promise.getId();
        if (regionAccess.withAccess(List.of(pocId), regionId).contains(pocId)) {
            notificationService.notify(pocId, NOTIF_BROKEN, title, message, link, regionId);
        } else {
            notificationService.notifyHolders(Privileges.PROMISE_MANAGE, regionId, currentUser.idOrNull(),
                    NOTIF_BROKEN, title, message, link);
        }
        promise.setBrokenNotifiedAt(clock.instant());
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
