package com.geneinvoice.dispute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalGate;
import com.geneinvoice.approval.DisputeExposure;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
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
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.payment.PaymentHistoryRepository;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DisputeService {

    public static final String ENTITY = "DISPUTE";

    private static final String NOTIF_OPENED = "DISPUTE_OPENED";
    private static final String NOTIF_APPROVED = "DISPUTE_APPROVED";
    private static final String NOTIF_DENIED = "DISPUTE_DENIED";

    private final DisputeRepository disputeRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    // The record a dispute is ABOUT, as it stood on the date asked about. A dispute raised
    // in January against an invoice that has since been edited must not be described with
    // today's number and today's total (B3).
    private final InvoiceHistoryRepository invoiceHistoryRepository;
    // The account's name as it stood then. dispute_history does not denormalise it — the
    // live row does not either — so it is a lookup on both paths, against the mirror under
    // an as-of date so that a disputes list and an invoices list of the same January name
    // the same account the same way (B3).
    private final CustomerHistoryRepository customerHistoryRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    // A single dispute is now read through the same funnel its list is, so a dispute on an account
    // in a branch the caller has nothing in reads as one that does not exist (B1, AUTH-08).
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    // The write-side gate: opening, approving and denying are all writes in the account's branch,
    // and a customer login is waved through because its reach is its own account (B1).
    private final RegionAccess regionAccess;
    // The approval gate goes on the dispute approval and never on the four inner mutators it
    // reaches; DisputeExposure is what turns a blob of proposed-change JSON into the figure the
    // gate measures AND into the priced text that figure was measured from, and ApprovalContext is
    // what stops the inner gates firing again (B2).
    private final ApprovalGate approvalGate;
    private final ApprovalContext approvalContext;
    private final DisputeExposure disputeExposure;
    // The region chips this list says it is narrowed by; empty for an unregioned table,
    // for a wildcard holder and for a customer login, so it is passed unconditionally (B1).
    private final RegionScope regionScope;
    // Which disputes have an approval waiting on them. The repository and not
    // ApprovalService: one query for a whole page rather than one per row (B2).
    private final com.geneinvoice.approval.PendingChangeRepository pendingChangeRepository;
    // Says so when a row in this answer was repaired rather than watched happen (B3).
    private final HistoryDrift historyDrift;

    @Transactional
    public Dispute open(DisputeDtos.CreateDisputeRequest req) {
        User caller = currentUser.require();
        Long callerCustomer = caller.getCustomerId();
        if (callerCustomer == null) {
            throw new AccessDeniedException("Only customers can open disputes");
        }
        // This is the one check that stays on the request side of the seam: a rule's author is
        // staff, so an automated dispute would fail it, while everything below it applies to both
        // paths and therefore lives in the shared body (A3).
        return openAs(caller.getId(), callerCustomer, req.targetType(), req.targetId(),
                req.reason(), req.proposedChangeJson());
    }

    /**
     * Part A's actor seam: open a dispute on behalf of a named actor rather than the logged-in
     * caller, because a rule runs with no SecurityContext and the accountable person is then the
     * rule's author (A3, A5).
     *
     * <p>proposedChangeJson is always null here: an automated dispute states the problem and
     * proposes nothing (A3).
     */
    @Transactional
    public Dispute openAs(Long actorUserId, Long customerId, DisputeTargetType type, Long targetId,
                          String reason) {
        return openAs(actorUserId, customerId, type, targetId, reason, null);
    }

    /**
     * The shared body both paths reach. Every region guard and every approval gate on opening a
     * dispute belongs HERE and never on one of the two callers above: a check on a wrapper is a
     * check the other path walks straight past (A3, B1, B2 INTEGRATION).
     */
    @Transactional
    public Dispute openAs(Long actorUserId, Long customerId, DisputeTargetType type, Long targetId,
                          String reason, String proposedChangeJson) {
        ensureTargetBelongsToCustomer(type, targetId, customerId);
        // The guard is in THIS body and not on either wrapper: both are self-invocations, so a
        // check on open() is skipped by every automated dispute and a check on the five-argument
        // openAs is skipped by every customer-raised one. RegionAccess returns early for a customer
        // login, which is the whole human path here (B1, A3, A5 INTEGRATION).
        regionAccess.requireManage(regionOf(customerId));

        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(
                customerId, type, targetId, DisputeStatus.PENDING)) {
            throw new BadRequestException("An open dispute already exists for this " +
                    type.name().toLowerCase());
        }

        Dispute d = Dispute.builder()
                .customerId(customerId)
                .openedByUserId(actorUserId)
                .targetType(type)
                .targetId(targetId)
                .reason(reason)
                .proposedChangeJson(proposedChangeJson)
                .status(DisputeStatus.PENDING)
                .build();
        d = disputeRepository.save(d);
        auditService.record(ENTITY, d.getId(), "DISPUTE_OPENED", null, toDto(d),
                actorUserId, d.getId(), reason);

        Customer cust = customerRepository.findById(customerId).orElse(null);
        String custName = cust == null ? "customer" : cust.getName();
        notificationService.notifyAdmins(NOTIF_OPENED,
                "New dispute from " + custName,
                reason,
                // The app has no /admin/disputes route; link where the dispute actually opens (D-53).
                "/disputes/" + d.getId());

        return d;
    }

    @Transactional
    public Dispute approve(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        // Reached by id, so the read gate answers FIRST and answers 404: refusing with 403 here
        // would tell a caller that a dispute they cannot see exists (B1, AUTH-08).
        requireInBook(disputeId);
        // Deciding a dispute applies a change to the invoice or payment it names, through the three
        // deliberate escape hatches in InvoiceService that read past the book and past the region
        // predicate. THIS is the check that stands for all of them: the approver must be able to
        // manage the branch the account is filed in (B1).
        regionAccess.requireManage(regionOf(d.getCustomerId()));
        String requested = req != null && req.appliedChangeJson() != null && !req.appliedChangeJson().isBlank()
                ? req.appliedChangeJson()
                : d.getProposedChangeJson();
        // THE AMOUNT APPROVED IS THE AMOUNT APPLIED. score() copies today's catalogue price into
        // any proposed line that names none and hands back the text it measured, so the figure the
        // checker reads, the payload the applier replays and the change applied below are all ONE
        // text. InvoiceService.frozen makes the same copy for a held INVOICE_CREATE, but a
        // DISPUTE_APPROVE carries the dispute's own proposedChangeJson as its payload and so is
        // not reached by that freeze; this is the same invariant kept by the same means, and not a
        // second mechanism (B2).
        DisputeExposure.Scored scored = disputeExposure.score(d, requested);
        String changeJson = scored.changeJson();
        User me = currentUser.require();

        // A dispute has always been a maker-checker of its own, and has never had either half of
        // the rule: the person who opened it could resolve it, and anyone holding DISPUTE_MANAGE
        // could resolve one on a record they cannot see. The second half is the requireInBook and
        // requireManage pair above; this is the first (B2).
        if (d.getOpenedByUserId() != null && d.getOpenedByUserId().equals(me.getId())) {
            throw new BadRequestException("You cannot approve a change you raised");
        }

        Object before = snapshotTarget(d);
        // The money this dispute would move is measured before it moves, and above the region's
        // limit the whole approval — apply, status, audit, notify — waits for a second pair of
        // eyes rather than each inner mutator waiting separately. A gate on an inner mutator
        // firing here would roll the dispute-status flip back and leave the dispute PENDING with
        // an orphaned pending change against an invoice instead of against the dispute (B2).
        //
        // targetVersion is null: a dispute carries no @Version, and ApprovalService.requireUnchanged
        // falls back to the stored value for a DISPUTE target rather than comparing one (B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.DISPUTE_APPROVE, disputeId, d.getCustomerId(),
                scored.exposure(), new ApprovalDtos.DisputeApproval(changeJson, parked(req, changeJson)),
                before, null,
                "Approve dispute #" + disputeId + " on " + d.getTargetType() + " " + d.getTargetId()));

        // Inside here the inner gates on voidPayment, updateAmount, cancelWithRefundForDispute-
        // Application and replaceItemsForDisputeApplication are a no-op: this change has already
        // been measured whole, by DisputeExposure, against the same figures they would use.
        //
        // null and not the id of a change being replayed: applyChange reaches no cascade that
        // consults ApprovalContext.applyingId(), and on a replay this nests inside the applier's
        // own applying(pc.getId(), ...), which restores its id on the way out (B2).
        // A final alias only because `d` is reassigned by the save below and a lambda needs an
        // effectively final capture; it is the same row (B2).
        Dispute applying = d;
        approvalContext.applying(null, () -> {
            applyChange(applying, changeJson);
            return null;
        });
        Object after = snapshotTarget(d);

        d.setStatus(DisputeStatus.APPROVED);
        d.setResolvedAt(java.time.Instant.now());
        d.setResolvedByUserId(me.getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);

        auditService.record(
                d.getTargetType().name(), d.getTargetId(),
                "DISPUTE_APPROVED",
                before, after,
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

        notifyCustomerOfResolution(d, NOTIF_APPROVED, "Dispute approved");
        return d;
    }

    /**
     * The resolve request as it is PARKED: its appliedChangeJson is the frozen text too. The
     * applier reads DisputeApproval.changeJson and rebuilds the request from that, so this copy is
     * never the one replayed — but a payload holding two texts that disagree is a payload whose
     * next reader can pick the wrong one, and the wrong one would be the unfrozen one (B2).
     */
    private static DisputeDtos.ResolveDisputeRequest parked(DisputeDtos.ResolveDisputeRequest req,
                                                            String changeJson) {
        return req == null ? null
                : new DisputeDtos.ResolveDisputeRequest(req.adminNotes(), changeJson);
    }

    @Transactional
    public Dispute deny(Long disputeId, DisputeDtos.ResolveDisputeRequest req) {
        Dispute d = mustBePending(disputeId);
        // The read gate first, and 404 rather than 403, exactly as approve does (B1, AUTH-08).
        requireInBook(disputeId);
        // Refusing a dispute is as much a decision on the account as approving one (B1).
        regionAccess.requireManage(regionOf(d.getCustomerId()));
        d.setStatus(DisputeStatus.DENIED);
        d.setResolvedAt(java.time.Instant.now());
        d.setResolvedByUserId(currentUser.require().getId());
        if (req != null && req.adminNotes() != null) d.setAdminNotes(req.adminNotes());
        d = disputeRepository.save(d);
        auditService.record(ENTITY, d.getId(), "DISPUTE_DENIED", null, toDto(d),
                d.getResolvedByUserId(), d.getId(),
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes());

        notifyCustomerOfResolution(d, NOTIF_DENIED, "Dispute denied");
        return d;
    }

    // list() is DELETED, for the same reason as the three finders named in this unit and found the
    // same way — it had no caller at all, and its staff branch was a bare findAll that read every
    // dispute in the company past the region predicate. GET /api/disputes goes through the query
    // funnel in DisputeController and is the scoped way to ask (B1).

    @Transactional(readOnly = true)
    public Dispute get(Long id) {
        Dispute d = disputeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(d.getCustomerId())) {
            throw new AccessDeniedException("Not allowed");
        }
        requireInBook(id);
        return d;
    }

    /**
     * The single-record gate the list already had for free. Disputes are the one list where staff
     * hold no book at all (forDisputes() is Scope.empty() for them), so before regions a member of
     * staff could read any dispute in the company by id; now the executor ANDs the region axis in
     * and one they cannot see answers exactly as a missing one does (B1, AUTH-08).
     */
    private void requireInBook(Long id) {
        if (!queryExecutor.inScope(Dispute.class, TableSchemas.DISPUTES, id,
                scopeResolver.forDisputes().predicates())) {
            throw new NotFoundException("Dispute not found");
        }
    }

    /**
     * WHERE A DISPUTE LIST READS FROM (B3). The InvoiceService.invoiceSource() shape exactly.
     * Disputes are the one list where staff hold no BOOK at all — forDisputes() is Scope.empty()
     * for them — so the scope list under {@code ?asOf} is the interval clause and whatever the
     * customer-login arm added, and nothing else.
     *
     * <p>{@code AsOf.at(T)} LEADS IT and is the line that makes the list count DISPUTES rather
     * than edits of disputes. The region axis is injected once by the executor for every root and
     * is already as-of correct (blueprint conflict 1, B1, B3).
     *
     * <p>The fetch list is empty on both sides: a dispute keeps every reference it has as a bare
     * Long with no association, which is why HistorySchemas.DISPUTES needed no column override
     * beyond the two region ones.
     */
    public AsOfSource<DisputeView> disputeSource() {
        ScopeResolver.Scope book = scopeResolver.forDisputes();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(Dispute.class, TableSchemas.DISPUTES, book.predicates(),
                    book.lockedFilters(), List.of());
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(DisputeHistory.class, HistorySchemas.DISPUTES,
                List.copyOf(scope), List.copyOf(locked), List.of());
    }

    /** Guarded outside the call because AsOfContext.instant() throws when nothing is open (B3). */
    private void markDrift() {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(DisputeHistory.class, AsOfContext.instant());
    }

    /**
     * The list, moved out of DisputeController so that it reads from one source switch like every
     * other list in the application does. The controller's body was the executor call inline; it
     * is byte-identical here on the live path (B3, B1).
     */
    @Transactional(readOnly = true)
    public PageResponse<DisputeDtos.DisputeDto> page(TableQuery query) {
        AsOfSource<DisputeView> source = disputeSource();
        var page = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch());
        markDrift();
        // One query for the whole page, and none at all for an empty one. Under an as-of date it
        // asks the decision LOG instead, inside openTargetIds, so this line does not move (B2, B3).
        Set<Long> held = pendingChangeRepository.openTargetIds(
                com.geneinvoice.approval.PendingTargetType.DISPUTE,
                page.content().stream().map(DisputeView::getId).toList());
        return PageResponse.of(
                page.content().stream().map(d -> toDto(d, held.contains(d.getId()))).toList(),
                query, page.total(), source.locked(), regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<DisputeView> source = disputeSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /** The rows behind an export, through the same source switch the list reads (B3). */
    @Transactional(readOnly = true)
    public List<? extends DisputeView> allMatching(TableQuery query) {
        AsOfSource<DisputeView> source = disputeSource();
        List<? extends DisputeView> rows = queryExecutor.run(source.type(), source.schema(),
                query, source.scope(), source.fetch()).content();
        markDrift();
        return rows;
    }

    /**
     * ONE DISPUTE, LIVE OR AS OF A DATE — and 404, never 403, when it did not exist then (B3).
     * R9 gave this endpoint its scoping for the first time; the as-of branch reads the mirror
     * through the same executor with an {@code id:eq:} filter, so there is no second copy of
     * {@link #get}'s checks to drift from the list.
     */
    @Transactional(readOnly = true)
    public DisputeDtos.DisputeDto detail(Long id) {
        if (!AsOfContext.isActive()) {
            Dispute d = get(id);
            // The sentinel column answers this in one indexed lookup; a decided change releases
            // its key, so only a change still waiting can be found here (B2).
            return toDto(d, pendingChangeRepository.existsByPendingKey(
                    com.geneinvoice.approval.PendingChange.keyOf(
                            com.geneinvoice.approval.PendingTargetType.DISPUTE, d.getId())));
        }
        Long callerCustomer = currentUser.customerIdOrNull();
        AsOfSource<DisputeView> source = disputeSource();
        List<? extends DisputeView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()
                || (callerCustomer != null && !callerCustomer.equals(rows.get(0).getCustomerId()))) {
            throw new NotFoundException("Dispute not found");
        }
        markDrift();
        return toDto(rows.get(0), pendingChangeRepository.openTargetIds(
                com.geneinvoice.approval.PendingTargetType.DISPUTE, List.of(id)).contains(id));
    }

    /** The branch the account is filed in. Disputes hold a bare customer_id, with no association. */
    private Long regionOf(Long customerId) {
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        return c.getRegion().getId();
    }

    /**
     * The snapshot shape: approvalPending is null, meaning "not asked". Every audit before/after
     * blob is built through this overload, so a DISPUTE_OPENED row written last March does not
     * change its meaning because somebody raised a change on the dispute this morning (B2).
     */
    public DisputeDtos.DisputeDto toDto(DisputeView d) {
        return toDto(d, null);
    }

    /**
     * The read shape: the list and the single-record GET have the flag in hand and pass it (B2).
     *
     * <p>The parameter is the VIEW and not the entity, so ONE mapper serves the live row and the
     * as-of mirror row and the two cannot drift apart. Source-compatible — Dispute implements
     * DisputeView, so no call site moves. A dispute carries its account as a flat customerId with
     * no association, so the widening costs nothing here (B3).
     */
    public DisputeDtos.DisputeDto toDto(DisputeView d, Boolean approvalPending) {
        Target target = describeTarget(d);
        String customerName = AsOfContext.isActive()
                ? customerHistoryRepository.inForce(List.of(d.getCustomerId()), AsOfContext.instant())
                        .stream().findFirst().map(CustomerHistory::getName).orElse(null)
                : customerRepository.findById(d.getCustomerId())
                        .map(Customer::getName).orElse(null);
        return new DisputeDtos.DisputeDto(
                d.getId(), d.getCustomerId(), customerName, d.getOpenedByUserId(),
                d.getTargetType(), d.getTargetId(), target.summary(), target.number(), target.amount(),
                d.getReason(), d.getProposedChangeJson(),
                d.getStatus(), d.getAdminNotes(),
                currentUser.isCustomer() ? null : d.getResolvedByUserId(), d.getResolvedAt(),
                d.getCreatedAt(), d.getUpdatedAt(),
                approvalPending);
    }

    private Dispute mustBePending(Long disputeId) {
        Dispute d = disputeRepository.findByIdForUpdate(disputeId)
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
        if (d.getStatus() != DisputeStatus.PENDING) {
            throw new BadRequestException("Dispute already resolved");
        }
        return d;
    }

    private void ensureTargetBelongsToCustomer(DisputeTargetType type, Long id, Long customerId) {
        switch (type) {
            case INVOICE -> {
                Invoice inv = invoiceRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Invoice not found"));
                if (!inv.getCustomer().getId().equals(customerId)) {
                    throw new AccessDeniedException("Not allowed");
                }
            }
            case PAYMENT -> {
                Payment p = paymentRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                if (!p.getCustomer().getId().equals(customerId)) {
                    throw new AccessDeniedException("Not allowed");
                }
            }
        }
    }

    private Object snapshotTarget(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(InvoiceDtos.InvoiceDto::from).orElse(null);
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(PaymentDtos.PaymentDto::from).orElse(null);
        };
    }

    private record Target(String number, BigDecimal amount, String summary) {}

    /**
     * The record this dispute is about, described as it stood on the date being answered (B3).
     *
     * <p>A dispute raised in January against an invoice that has been edited since must not be
     * labelled with today's number and today's total — that is the same leak the flat customer_id
     * closes for the account. So under an open context the two lookups read the invoice and
     * payment mirrors in force at T instead of the live tables, and a record with no version in
     * force then falls through to the same "Invoice #7" placeholder a deleted record already gives.
     *
     * <p>ONE LOOKUP PER ROW, on both paths. That is a pre-existing N+1 on the live list, inherited
     * rather than introduced, and it is stated here rather than quietly doubled.
     */
    private Target describeTarget(DisputeView d) {
        if (AsOfContext.isActive()) {
            Instant at = AsOfContext.instant();
            return switch (d.getTargetType()) {
                case INVOICE -> invoiceHistoryRepository.inForce(List.of(d.getTargetId()), at)
                        .stream().findFirst()
                        .map((InvoiceHistory i) -> new Target(i.getInvoiceNumber(), i.getTotal(),
                                i.getInvoiceNumber() + " — " + i.getTotal()))
                        .orElse(new Target(null, null, "Invoice #" + d.getTargetId()));
                case PAYMENT -> paymentHistoryRepository.inForce(List.of(d.getTargetId()), at)
                        .stream().findFirst()
                        .map((PaymentHistory p) -> new Target("#" + p.getId(), p.getAmount(),
                                "Payment #" + p.getId() + " — " + p.getAmount()))
                        .orElse(new Target(null, null, "Payment #" + d.getTargetId()));
            };
        }
        return switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(i -> new Target(i.getInvoiceNumber(), i.getTotal(),
                            i.getInvoiceNumber() + " — " + i.getTotal()))
                    .orElse(new Target(null, null, "Invoice #" + d.getTargetId()));
            case PAYMENT -> paymentRepository.findById(d.getTargetId())
                    .map(p -> new Target("#" + p.getId(), p.getAmount(),
                            "Payment #" + p.getId() + " — " + p.getAmount()))
                    .orElse(new Target(null, null, "Payment #" + d.getTargetId()));
        };
    }

    private void applyChange(Dispute d, String changeJson) {
        if (changeJson == null || changeJson.isBlank()) {
            throw new BadRequestException("No change specified for approval");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(changeJson);
        } catch (Exception e) {
            throw new BadRequestException("The change is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("The change must be a JSON object");
        }
        String action = node.path("action").asText("");

        switch (d.getTargetType()) {
            case INVOICE -> applyInvoiceChange(d.getTargetId(), action, node);
            case PAYMENT -> applyPaymentChange(d.getTargetId(), action, node);
        }
    }

    private void applyInvoiceChange(Long invoiceId, String action, JsonNode node) {
        switch (action) {
            case "cancel" -> invoiceService.cancelWithRefundForDisputeApplication(invoiceId);
            case "replace_items" -> {
                JsonNode itemsNode = node.path("items");
                if (!itemsNode.isArray() || itemsNode.isEmpty()) {
                    throw new BadRequestException("replace_items requires non-empty items array");
                }
                List<InvoiceDtos.LineInput> items = new ArrayList<>();
                for (JsonNode it : itemsNode) {
                    items.add(new InvoiceDtos.LineInput(wholeNumber(it, "productId"),
                            wholeInt(it, "quantity"), decimal(it, "unitPrice")));
                }
                invoiceService.replaceItemsForDisputeApplication(invoiceId, items,
                        text(node, "notes", FieldLimits.INVOICE_NOTES));
            }
            case "update_notes" -> {
                Invoice inv = invoiceService.getInternalForDisputeApplication(invoiceId);
                inv.setNotes(text(node, "notes", FieldLimits.INVOICE_NOTES));
                invoiceRepository.save(inv);
            }
            default -> throw new BadRequestException("Unknown invoice action: " + action);
        }
    }

    private void applyPaymentChange(Long paymentId, String action, JsonNode node) {
        switch (action) {
            case "void" -> paymentService.voidPayment(paymentId);
            case "update_amount" -> {
                BigDecimal amount = decimal(node, "amount");
                if (amount == null) {
                    throw new BadRequestException("update_amount requires amount");
                }
                // Here, beside the parse, and not left to paymentService.updateAmount: a proposed
                // change arrives as free-form JSON that no @Digits annotation ever saw, and this
                // is the first point at which the figure exists. A three-decimal amount is refused
                // before the reversal-and-re-application starts rather than in the middle of it (B2).
                Money.requireCents(amount, "Amount");
                paymentService.updateAmount(paymentId, amount,
                        text(node, "method", FieldLimits.PAYMENT_METHOD),
                        text(node, "notes", FieldLimits.PAYMENT_NOTES));
            }
            case "update_meta" -> {
                Payment p = paymentRepository.findById(paymentId)
                        .orElseThrow(() -> new NotFoundException("Payment not found"));
                String method = text(node, "method", FieldLimits.PAYMENT_METHOD);
                String notes = text(node, "notes", FieldLimits.PAYMENT_NOTES);
                if (method != null) p.setMethod(method);
                if (notes != null) p.setNotes(notes);
                paymentRepository.save(p);
            }
            default -> throw new BadRequestException("Unknown payment action: " + action);
        }
    }

    private static long wholeNumber(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isIntegralNumber() && v.canConvertToLong()) return v.asLong();
        if (v != null && v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new BadRequestException(field + " must be a whole number");
    }

    private static int wholeInt(JsonNode node, String field) {
        long value = wholeNumber(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BadRequestException(field + " is out of range");
        }
        return (int) value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new BadRequestException(field + " must be a number");
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String value = v.asText();
        if (value.length() > maxLength) {
            throw new BadRequestException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private void notifyCustomerOfResolution(Dispute d, String type, String title) {
        notificationService.notify(
                d.getOpenedByUserId(),
                type,
                title,
                d.getAdminNotes() == null ? d.getReason() : d.getAdminNotes(),
                "/disputes/" + d.getId());
    }
}
