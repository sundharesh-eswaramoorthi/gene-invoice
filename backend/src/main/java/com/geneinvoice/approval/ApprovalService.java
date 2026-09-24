package com.geneinvoice.approval;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
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
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What happens to a save the gate refused: it is written down, and the maker is told in a sentence
 * that says both what did not happen and what will (B2).
 *
 * <p>And what happens to it afterwards: approved, rejected or withdrawn. The decision half is the
 * one that touches money, and it is four layers deep on purpose — the payload version, the row
 * version, the mutator's own guards re-run against live state, and one open change per record —
 * because between the save and the decision the record can have become something nobody agreed
 * to (B2).
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    public static final String ENTITY = "PENDING_CHANGE";

    private static final String CHANGE_REQUESTED = "CHANGE_REQUESTED";
    private static final String CHANGE_APPROVED = "CHANGE_APPROVED";
    private static final String CHANGE_REJECTED = "CHANGE_REJECTED";
    private static final String CHANGE_WITHDRAWN = "CHANGE_WITHDRAWN";

    /** The notification type, not an audit action: nothing is recorded when a change is raised
     *  beyond the CHANGE_REQUESTED row itself (B2). */
    private static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";

    /** The blueprint's wording, and 403 rather than a rejection: the change STAYS PENDING, because
     *  somebody losing a grant is not a judgement on the change they raised (B2, B1 INTEGRATION). */
    static final String MAKER_LOST_REGION =
            "The person who raised this no longer has manage access in that region";

    /** The anchor an audit row falls back to for a create with no customer either. AuditLog.entityId
     *  is nullable=false, and a row nobody can find is still better than no row at all (B2). */
    private static final Long NO_ANCHOR = 0L;

    private final PendingChangeRepository repository;
    private final RegionLookup regions;
    private final AuditService auditService;
    private final PendingChangeApplier applier;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    // The two the row-version precondition reads through a repository. It takes the CUSTOMER's
    // lock FIRST, because that is the lock every money writer already takes first, and then the
    // target's own — payments and promises included, which the customer lock does not serialise
    // at all (PPD-01, B2).
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    // And those two through the EntityManager rather than through their own repositories: this
    // precondition is the only code in the application that locks a payment or a promise row, and
    // a findByIdForUpdate on PaymentRepository would advertise to every other money writer an
    // order that none of them takes (PPD-01, B2).
    @PersistenceContext
    private EntityManager entityManager;
    // The read model. The queue is an ordinary table endpoint, so it gets B1's region predicate,
    // the locked chips, sorting and paging from the same funnel every other list uses (B2, B1).
    private final TableQueryExecutor queryExecutor;
    private final ScopeResolver scopeResolver;
    // The SECOND class in this package to name com.geneinvoice.region, after RegionLookupImpl and
    // PendingChangeRegionSync: the region chips a page says it is already narrowed by are
    // PageResponse.of's fifth argument and there is no other source for them (B2, B1 INTEGRATION).
    private final RegionScope regionScope;

    /**
     * Write the held change down and describe it back to the maker.
     *
     * <p>PUBLIC and MANDATORY, which is the blueprint's amendment to B2's original: Part A's
     * durable executor parks from inside a transaction it opened itself after the business
     * transaction rolled back, so this must JOIN rather than open one of its own. The caller is
     * therefore responsible for the transaction — GlobalExceptionHandler and BulkExecutor each
     * wrap it in a REQUIRES_NEW template, because by the time they run the mutator's transaction
     * is already gone (B2, A5 INTEGRATION).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDtos.Accepted park(PendingChange change, String path) {
        PendingChange saved = repository.save(change);
        // The business transaction rolled back and took its own audit rows with it, so without
        // this a maker could probe the gate all day — how much goes through, whose account holds
        // what — and leave no trace anywhere (B2).
        auditService.record(anchorType(saved), anchorId(saved), CHANGE_REQUESTED,
                null, ApprovalDtos.PendingChangeDto.of(saved), saved.getRequestedByUserId(),
                null, saved.getId(), saved.getSummary());
        // And tell the people who can actually do something about it. notifyAdmins is not that
        // set: it resolves the literal role name "ADMIN" with no region and no active filter,
        // where this asks the region side who holds APPROVAL_APPROVE here (B2, B1 INTEGRATION).
        // The maker is left out because they are the one person who may not decide it; a change
        // the engine raised has no maker to leave out and tells everybody (B2, A5).
        notificationService.notifyEach(
                regions.usersWith(Privileges.APPROVAL_APPROVE, saved.getRegionId()),
                saved.getRequestedByUserId(), APPROVAL_REQUESTED, "A change needs approval",
                saved.getSummary(), "/approvals/" + saved.getId());
        return accepted(saved, path, message(saved));
    }

    /**
     * The sentence for the race the database caught: two makers both passed the gate's
     * in-transaction exists() because the rollback released the target's row lock before either
     * row was written, and uq_pending_open let exactly one of them through. The loser is told
     * which change is there rather than being handed a bare conflict (B2).
     *
     * <p>It takes the change that could NOT be written and re-reads by its pending_key, because
     * the caller that catches the violation is an exception handler with no repository of its own.
     */
    @Transactional(readOnly = true)
    public ApprovalDtos.Accepted describeExisting(PendingChange attempted, String path) {
        String key = PendingChange.keyOf(attempted.getTargetType(), attempted.getTargetId());
        PendingChange existing = key == null ? null : repository.findByPendingKey(key).orElse(null);
        if (existing == null) {
            // Decided between the failed insert and this read, or the violation was something
            // else entirely. Say what is true rather than naming a change that is not there (B2).
            return accepted(attempted, path,
                    "This change could not be sent for approval; reload and try again");
        }
        return accepted(existing, path,
                "A change on this record is already waiting for approval (change #"
                        + existing.getId() + ")");
    }

    // ------------------------------------------------------------------ the decision half (B2)

    /**
     * One change, answered for the person looking at it. A change in a branch the caller holds
     * nothing in answers exactly as a missing one does, which is AUTH-08 and is never relaxed (B2).
     */
    @Transactional(readOnly = true)
    public ApprovalDtos.PendingChangeDto get(Long id) {
        User me = currentUser.require();
        PendingChange pc = AsOfContext.isActive() ? outstandingThen(id) : repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Change not found"));
        requireVisible(pc, me);
        return describe(pc, me.getId(), mayDecideIn(pc.getRegionId()));
    }

    /**
     * The one change, IF it was still waiting on the date asked about — and 404 otherwise, whether
     * it had not been raised yet or had already been decided (B3, AUTH-08).
     *
     * <p>Through the same executor, the same schema and the same scope list the queue itself uses,
     * with an {@code id:eq:} filter and nothing else added, so the single record and the list
     * cannot disagree about which changes were outstanding then.
     */
    private PendingChange outstandingThen(Long id) {
        AsOfSource<PendingChange> source = approvalSource();
        List<? extends PendingChange> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()) throw new NotFoundException("Change not found");
        return rows.get(0);
    }

    /**
     * WHICH APPROVALS WERE OUTSTANDING, AND IT NEEDS NO MIRROR TABLE AT ALL (B3).
     *
     * <p>This is the second half of the PRD clause, and it is the cheapest correct answer in the
     * design: pending_changes is ALREADY interval-shaped. {@code requestedAt} opens the interval
     * and {@code decidedAt} closes it, so {@link AsOf#outstandingAt} reads the decision log as the
     * history it already is — which is exactly why PendingChange is deliberately absent from the
     * mirrored set and why B3-WRITER's ApprovalBulkTest pins that a held row writes no mirror row.
     *
     * <p>THE ROOT AND THE SCHEMA DO NOT MOVE, and that is the point: the only thing that changes
     * under {@code ?asOf} is the scope list. So every column, every filter, every sort and every
     * tile on this list keeps working, and "what was waiting on 31 January, in which branch" is
     * exact — {@code pending_changes.region_id} is stamped at raise time and never mutated
     * (blueprint conflict 84 replaced B2's re-stamp-on-move with supersede-on-move precisely so
     * that this column stays the frozen raise-time region).
     *
     * <p>ITS RIDER IS LOAD-BEARING: every terminal transition must set {@code decided_at}, or a
     * superseded row reports as outstanding for ever and the live answer never moves to say so.
     * AsOfRegionAndApprovalTest asserts it for all four.
     */
    public AsOfSource<PendingChange> approvalSource() {
        ScopeResolver.Scope book = scopeResolver.forApprovals();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(PendingChange.class, ApprovalSchemas.APPROVALS,
                    book.predicates(), book.lockedFilters(), List.of());
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.outstandingAt(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(PendingChange.class, ApprovalSchemas.APPROVALS,
                List.copyOf(scope), List.copyOf(locked), List.of());
    }

    // ------------------------------------------------------------------- the read model (B2)

    /**
     * The queue itself, as an ordinary table endpoint (B2).
     *
     * <p>PendingChange is classified OWN_ID, so the region clause is ANDed in by
     * TableQueryExecutor before ScopeResolver has said anything: a caller sees the changes raised
     * in the branches they work in and no others, and a change in a branch they hold nothing in
     * is absent rather than refused (AUTH-08, B2, B1).
     */
    @Transactional(readOnly = true)
    public PageResponse<ApprovalDtos.PendingChangeDto> page(TableQuery query) {
        User me = currentUser.require();
        AsOfSource<PendingChange> source = approvalSource();
        TableQueryExecutor.Page<? extends PendingChange> page = queryExecutor.run(
                source.type(), source.schema(), query, source.scope(), source.fetch());
        return PageResponse.of(describe(page.content(), me.getId()), query, page.total(),
                source.locked(), regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<PendingChange> source = approvalSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /** The export's rows: the same query, unpaged, through the same scope (B2). */
    @Transactional(readOnly = true)
    public List<ApprovalDtos.PendingChangeDto> allMatching(TableQuery query) {
        User me = currentUser.require();
        AsOfSource<PendingChange> source = approvalSource();
        return describe(queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch()).content(), me.getId());
    }

    /**
     * Nine figures, ONE aggregate, over exactly the set the list is showing (B2).
     *
     * <p>{@code pendingExposure} is money that has NOT moved and is reported on its own rather
     * than netted into anything: every other total in this application is money that has moved,
     * and a figure mixing the two is wrong on whichever screen it appears (B2).
     */
    @Transactional(readOnly = true)
    public ApprovalDtos.ApprovalSummaryTiles tiles(TableQuery query) {
        User me = currentUser.require();
        AsOfSource<PendingChange> source = approvalSource();
        // Read once, outside the lambda: approvableRegions is a query, and the aggregate builder
        // runs inside criteria construction where a second round trip is nobody's expectation (B2).
        boolean anywhere = currentUser.has(Privileges.APPROVAL_APPROVE_ANY);
        List<Long> approvable = anywhere ? List.of() : regions.approvableRegions(me.getId());
        Long meId = me.getId();

        // The nine selection lambdas are UNCHANGED, and under ?asOf they are counted over the set
        // that was OUTSTANDING then rather than over every change ever raised — the status tiles
        // then report how those still-waiting changes were eventually decided (B2, B3).
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(),
                query, source.scope(), (root, q, cb) -> {
                    Predicate pending = cb.equal(root.get("status"), PendingChangeStatus.PENDING);
                    Predicate mine = cb.equal(root.get("requestedByUserId"), meId);
                    // A wildcard approver may decide anywhere; an empty list is an empty answer
                    // and never `in ()`, which is a syntax error on Postgres (B2, B1).
                    Predicate here = anywhere ? cb.conjunction()
                            : approvable.isEmpty() ? cb.disjunction()
                            : root.get("regionId").in(approvable);
                    // `<> :me` alone is NULL for an engine-raised change, which would drop every
                    // automated change out of the tile that exists to count them (B2, A5).
                    Predicate notMine = cb.or(cb.isNull(root.get("requestedByUserId")),
                            cb.notEqual(root.get("requestedByUserId"), meId));
                    return List.of(
                            cb.count(root.get("id")),
                            Aggregates.countWhen(cb, pending),
                            Aggregates.sumWhen(cb, pending, root.get("exposure")),
                            Aggregates.countWhen(cb, mine),
                            Aggregates.countWhen(cb, cb.and(pending, here, notMine)),
                            Aggregates.countWhen(cb,
                                    cb.equal(root.get("status"), PendingChangeStatus.APPROVED)),
                            Aggregates.countWhen(cb,
                                    cb.equal(root.get("status"), PendingChangeStatus.REJECTED)),
                            Aggregates.countWhen(cb,
                                    cb.equal(root.get("status"), PendingChangeStatus.WITHDRAWN)),
                            Aggregates.countWhen(cb,
                                    cb.equal(root.get("status"), PendingChangeStatus.SUPERSEDED)));
                });

        return new ApprovalDtos.ApprovalSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asLong(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]), Aggregates.asLong(row[5]),
                Aggregates.asLong(row[6]), Aggregates.asLong(row[7]), Aggregates.asLong(row[8]));
    }

    /**
     * The four name fields PendingChangeDto.of cannot fill, resolved once for a whole page (B2).
     *
     * <p>The data layer has no name source — a PendingChange keeps raw Longs for the customer,
     * the region and both people, the cross-aggregate house convention — so a queue built from
     * of(...) alone would show a reader four id numbers and no sentence. Batched rather than
     * per-row: a page of 25 changes costs one customer read and one user read whatever it holds.
     */
    private List<ApprovalDtos.PendingChangeDto> describe(List<? extends PendingChange> rows,
                                                        Long meId) {
        if (rows.isEmpty()) return List.of();

        Map<Long, String> customerNames = new HashMap<>();
        List<Long> customerIds = rows.stream().map(PendingChange::getCustomerId)
                .filter(Objects::nonNull).distinct().toList();
        if (!customerIds.isEmpty()) {
            customerRepository.findAllById(customerIds)
                    .forEach(c -> customerNames.put(c.getId(), c.getName()));
        }

        Map<Long, String> peopleNames = new HashMap<>();
        List<Long> userIds = Stream.concat(
                        rows.stream().map(PendingChange::getRequestedByUserId),
                        rows.stream().map(PendingChange::getDecidedByUserId))
                .filter(Objects::nonNull).distinct().toList();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds)
                    .forEach(u -> peopleNames.put(u.getId(), u.getFullName()));
        }

        // Bounded by the number of DISTINCT branches on the page, which a region-scoped caller
        // can only have a handful of, and every one of them is a primary-key read the persistence
        // context has usually already answered (B2, B1).
        Map<Long, String> regionNames = new HashMap<>();
        rows.stream().map(PendingChange::getRegionId).filter(Objects::nonNull).distinct()
                .forEach(id -> regionNames.put(id, regions.regionName(id)));

        return rows.stream().map(c -> named(
                ApprovalDtos.PendingChangeDto.of(c, meId, mayDecideIn(c.getRegionId())),
                customerNames.get(c.getCustomerId()), regionNames.get(c.getRegionId()),
                peopleNames.get(c.getRequestedByUserId()),
                peopleNames.get(c.getDecidedByUserId()))).toList();
    }

    /** The same four names for ONE change — a single GET, or the row a decision just closed. */
    private ApprovalDtos.PendingChangeDto describe(PendingChange c, Long meId, boolean rightInRegion) {
        return named(ApprovalDtos.PendingChangeDto.of(c, meId, rightInRegion),
                c.getCustomerId() == null ? null : customerRepository.findById(c.getCustomerId())
                        .map(Customer::getName).orElse(null),
                regions.regionName(c.getRegionId()),
                nameOf(c.getRequestedByUserId()), nameOf(c.getDecidedByUserId()));
    }

    private String nameOf(Long userId) {
        return userId == null ? null
                : userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // Rebuilt rather than mutated because PendingChangeDto is a record, and built HERE rather
    // than as a third factory on ApprovalDtos because the audit snapshot shape must stay the one
    // with no names in it: of(PendingChange) is what every before/after blob is written from (B2).
    private static ApprovalDtos.PendingChangeDto named(ApprovalDtos.PendingChangeDto d,
                                                       String customerName, String regionName,
                                                       String requestedByName, String decidedByName) {
        return new ApprovalDtos.PendingChangeDto(d.id(), d.action(), d.targetType(), d.targetId(),
                d.customerId(), customerName, d.regionId(), regionName, d.summary(), d.exposure(),
                d.thresholdApplied(), d.alwaysChecked(), d.status(), d.requestedByUserId(),
                requestedByName, d.requestedAt(), d.decidedByUserId(), decidedByName,
                d.decidedAt(), d.decisionNotes(), d.payloadJson(), d.beforeJson(),
                d.targetVersion(), d.batchId(), d.mine(), d.canDecide(), d.cannotDecideReason());
    }

    // ------------------------------------------------------------- one bulk run's changes (B2)

    /**
     * Every change one bulk run raised and has still waiting, for the person looking at it (B2).
     *
     * <p>THE BATCH METHOD. It answers ids and not rows, and it decides nothing: the caller runs
     * each id through BulkExecutor so every decision is its own REQUIRES_NEW transaction and one
     * change that can no longer be applied does not take the other forty-nine with it. That is
     * also why the loop cannot live here — BulkExecutor already depends on this class to park a
     * held row, and a field the other way would be a circular reference Spring Boot 3 refuses to
     * build (B2).
     *
     * <p>PENDING only, because deciding a batch is deciding what is still waiting; and narrowed to
     * what this caller can see, so a batch raised in a branch they hold nothing in reads as an
     * empty batch rather than as a list of ids they may not have (AUTH-08, B2).
     */
    @Transactional(readOnly = true)
    public List<Long> batchIds(String batchId) {
        if (batchId == null || batchId.isBlank()) return List.of();
        User me = currentUser.require();
        return repository.findByBatchIdAndStatus(batchId, PendingChangeStatus.PENDING).stream()
                .filter(c -> visibleTo(c, me))
                .map(PendingChange::getId)
                .sorted()
                .toList();
    }

    /**
     * The second pair of eyes says yes, and the save that did not happen happens now (B2).
     *
     * <p>Four layers stand between the decision and the replay, in this order and no other: the
     * payload version, the approver's own rights, the row version of the record the change was
     * composed against, and then the mutator's own guards, which run again because the applier
     * replays the CALL. Only the first and third mark the change nothing — a mutator refusing on
     * live state leaves the row PENDING, because auto-rejecting on a transient conflict would
     * lose the maker's work (B2).
     */
    @Transactional
    public ApprovalDtos.Decision approve(Long id, ApprovalDtos.DecisionRequest req) {
        PendingChange pc = locked(id);
        User me = currentUser.require();
        // Visibility BEFORE status: a change this caller may not see answers as a missing one
        // does, decided or not, or the 400 below is an oracle over every id in the table
        // (AUTH-08, B2).
        guardApprover(pc, me);
        mustBePending(pc);
        requireUnchanged(pc);

        Object after = asMaker(pc, () -> applier.apply(pc));

        return decided(pc, me, PendingChangeStatus.APPROVED, CHANGE_APPROVED,
                req == null ? null : req.decisionNotes(), after,
                "Your change was approved");
    }

    /**
     * No, and here is why. A rejection without a reason is a rejection nobody can act on, which is
     * why RejectRequest asks for what DecisionRequest leaves optional (B2).
     */
    @Transactional
    public ApprovalDtos.Decision reject(Long id, ApprovalDtos.RejectRequest req) {
        PendingChange pc = locked(id);
        User me = currentUser.require();
        guardApprover(pc, me);
        mustBePending(pc);
        if (req == null || req.decisionNotes() == null || req.decisionNotes().isBlank()) {
            throw new BadRequestException("Say why this change is being rejected");
        }
        // Nothing is replayed, so nothing about the record is read: a change can be rejected long
        // after the record it was about has moved on, and refusing to reject it would leave the
        // queue holding work nobody can clear (B2).
        return decided(pc, me, PendingChangeStatus.REJECTED, CHANGE_REJECTED,
                req.decisionNotes(), null, "Your change was rejected");
    }

    /**
     * The maker taking their own change back, or an approver in its branch clearing the queue.
     * Not a rejection: nobody judged it, so the maker is not told their change was refused (B2).
     */
    @Transactional
    public ApprovalDtos.Decision withdraw(Long id, ApprovalDtos.DecisionRequest req) {
        PendingChange pc = locked(id);
        User me = currentUser.require();
        guardWithdrawer(pc, me);
        mustBePending(pc);
        return decided(pc, me, PendingChangeStatus.WITHDRAWN, CHANGE_WITHDRAWN,
                req == null ? null : req.decisionNotes(), null, null);
    }

    /**
     * The one place a change stops waiting. EVERY terminal transition sets decided_at — the
     * blueprint's mandated rider — or B3's AsOf.outstandingAt(T) reports a decided row as
     * outstanding for ever (B2, B3 INTEGRATION).
     */
    private ApprovalDtos.Decision decided(PendingChange pc, User me, PendingChangeStatus status,
                                          String action, String notes, Object after,
                                          String notifyTitle) {
        pc.setStatus(status);
        pc.setDecidedByUserId(me.getId());
        pc.setDecidedAt(Instant.now());
        if (notes != null) pc.setDecisionNotes(notes);
        // saveAndFlush and not save: the @PreUpdate that releases pending_key runs at flush, and
        // Hibernate runs every INSERT before every UPDATE. Anything that closes a change and
        // raises its replacement in one transaction would otherwise meet uq_pending_open, which
        // is the trap RegionCustodyService.move already documents on uk_crh_open (B2, B1).
        PendingChange saved = repository.saveAndFlush(pc);
        auditService.record(anchorType(saved, after), anchorId(saved, after), action, null,
                ApprovalDtos.PendingChangeDto.of(saved), me.getId(), null, saved.getId(),
                saved.getDecisionNotes());
        // A change with no maker was raised by the automation engine and there is nobody to tell;
        // and somebody withdrawing their own change does not need a notification about it (B2, A5).
        if (notifyTitle != null && saved.getRequestedByUserId() != null
                && !saved.getRequestedByUserId().equals(me.getId())) {
            notificationService.notify(saved.getRequestedByUserId(), action, notifyTitle,
                    saved.getSummary(), "/approvals/" + saved.getId());
        }
        // The names are filled here too: a decision screen that came back with four bare id
        // numbers would be the one place in the queue a reader could not read (B2).
        return new ApprovalDtos.Decision(describe(saved, me.getId(), true), after);
    }

    /**
     * The change, under the row's own write lock, so two approvers reaching one change serialise
     * on the row rather than both reading PENDING and both applying it. @Version on
     * pending_changes is the backstop if they somehow do (B2).
     *
     * <p>THE LOCK ORDER, and the one rule every decision here obeys: the CUSTOMER's row first,
     * the change's row second, and nothing before the customer. It is PPD-01's order — the money
     * path takes the customer, then invoices ascending by id — and it is also the order the two
     * writers that reach both tables already take: RegionCustodyService.move locks the customer
     * and then supersedes that account's waiting changes through its CustomerMoved listener, and
     * CustomerService.delete does the same through PendingChangeCascade. Taking the change's row
     * first would close an ABBA cycle with either of them, and a Postgres deadlock detector
     * aborting one side at random is how it would be reported (PPD-01, B2, B1 INTEGRATION).
     */
    private PendingChange locked(Long id) {
        // A projection and NOT the entity: finding out whose account this change is about must
        // not be what loads the row, or the write lock below would arrive second, on a copy the
        // persistence context is already holding at whatever it read (B2).
        repository.customerIdOf(id).ifPresent(customerRepository::findByIdForUpdate);
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Change not found"));
    }

    // Asked AFTER the caller has been shown to be someone who may see this change at all: a 400
    // naming a decided change that the caller cannot see is an existence oracle over the whole
    // table, and out of scope reads as missing here exactly as everywhere else (AUTH-08, B2).
    private void mustBePending(PendingChange pc) {
        if (pc.getStatus() != PendingChangeStatus.PENDING) {
            throw new BadRequestException("This change has already been decided");
        }
    }

    /**
     * Who may decide this, in this order and no other (B2).
     */
    private void guardApprover(PendingChange pc, User me) {
        // A customer login never decides a staff change.
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        requireVisible(pc, me);
        // Visible but not approvable: the ACTION is forbidden, which has always been 403 here.
        if (!mayDecideIn(pc.getRegionId())) {
            throw new AccessDeniedException("You do not hold the approval right in this region");
        }
        // "Approval from someone else" — never waived, not even by APPROVAL_APPROVE_ANY. A change
        // with no maker was raised by the automation engine (A5); everyone is then someone else.
        if (pc.getRequestedByUserId() != null && pc.getRequestedByUserId().equals(me.getId())) {
            throw new AccessDeniedException("You cannot approve a change you raised");
        }
    }

    /** Taking it back is the maker's own, or any approver's in its branch, and nobody else's (B2). */
    private void guardWithdrawer(PendingChange pc, User me) {
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        if (pc.getRequestedByUserId() != null && pc.getRequestedByUserId().equals(me.getId())) return;
        requireVisible(pc, me);
        if (!mayDecideIn(pc.getRegionId())) {
            throw new AccessDeniedException(
                    "Only the person who raised this, or an approver in its region, can withdraw it");
        }
    }

    // A change on a record in a region the caller holds nothing in answers exactly as a missing
    // one does, the way every out-of-scope read already does (AUTH-08, B2).
    private void requireVisible(PendingChange pc, User me) {
        if (!visibleTo(pc, me)) throw new NotFoundException("Change not found");
    }

    // The same rule as a question rather than as a refusal, because a batch answers for a LIST of
    // changes and a list cannot throw per row. One rule, two callers (AUTH-08, B2).
    private boolean visibleTo(PendingChange pc, User me) {
        if (me.getCustomerId() != null) return false;
        return currentUser.has(Privileges.APPROVAL_APPROVE_ANY)
                || regions.hasAnyRight(me.getId(), pc.getRegionId());
    }

    private boolean mayDecideIn(Long regionId) {
        return currentUser.has(Privileges.APPROVAL_APPROVE_ANY)
                || currentUser.has(Privileges.APPROVAL_APPROVE, regionId);
    }

    /**
     * Runs the replay under the principal of the person who asked for the change (B2).
     *
     * <p>This is not only about attribution. Every gated mutator re-checks the POC book —
     * InvoiceService.update's requireInBook, PaymentService.get, PaymentPromiseService.get — and
     * those checks are about the MAKER's book, not the approver's. An approver authorised by
     * region but outside the maker's book would otherwise get 404 from their own approval. It also
     * keeps pocService.requireAssignable, currentUser.canAssignPoc and the actor on every audit
     * row the mutator writes pointing at the maker, which is who made the change.
     *
     * <p>The approver's own rights were checked in guardApprover, before the swap.
     */
    private Object asMaker(PendingChange pc, Supplier<Object> work) {
        if (pc.getRequestedByUserId() == null) return work.get();   // raised by the engine (A5)
        // findById and not a cached instance, so B1's EAGER user.getRegionGrants() come along and
        // the principal below is the maker as they are NOW, not as they were when they saved (B2, B1).
        User maker = userRepository.findById(pc.getRequestedByUserId())
                .orElseThrow(() -> new StaleChangeException("The person who raised this no longer exists"));
        // Dismissing somebody has to stop their queued money, not let it through later (B2).
        if (!maker.isActive()) {
            throw new StaleChangeException("The person who raised this change is no longer active");
        }
        // Asked here rather than left to the replay, because a maker who now holds NOTHING in
        // that branch meets the region read predicate first and is refused 404 — "the record you
        // cannot see" — which tells the approver nothing about why their approval failed. A maker
        // downgraded from MANAGE to VIEW still gets past this and is caught below by
        // regionAccess, under the same sentence (B2, B1 INTEGRATION).
        if (!regions.hasAnyRight(maker.getId(), pc.getRegionId())) {
            throw new AccessDeniedException(MAKER_LOST_REGION);
        }
        // R3 deliberately kept this constructor signature (User, List<GrantedAuthority>) and
        // computes RegionGrants internally, so this line compiles unchanged under B1 (B2, B1).
        AppUserDetails principal = new AppUserDetails(maker, AppUserDetailsService.buildAuthorities(maker));
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContextHolder.setContext(new SecurityContextImpl(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
            return work.get();
        } catch (AccessDeniedException e) {
            // The maker is the only principal on this thread, so a refusal here is the MAKER being
            // refused: B1 drops a privilege they can exercise in no region, and regionAccess
            // refuses a write in a branch they have since lost. Said out loud rather than surfaced
            // as the approver's own 403 — and the change STAYS PENDING and is never auto-rejected,
            // because losing a grant is not a judgement on the change (B2, B1 INTEGRATION).
            throw new AccessDeniedException(MAKER_LOST_REGION);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * The row-version precondition: the record the approver is agreeing to is the record the maker
     * composed the change against, or nobody replays anything (B2).
     */
    private void requireUnchanged(PendingChange pc) {
        if (pc.getTargetId() == null) return;                    // a create has no target
        // Neither a dispute nor a region carries a row version, so there is nothing to compare and
        // nothing to read as gone: DisputeService.mustBePending and the threshold row's own write
        // lock are the whole precondition there (B2).
        if (pc.getTargetType() == PendingTargetType.DISPUTE
                || pc.getTargetType() == PendingTargetType.REGION) {
            return;
        }
        // The customer's row first, the one order the whole money path takes its locks in: an
        // approval runs concurrently with live payments on the same customer (PPD-01, B2). Held
        // already by locked(id) above, which is where that order is decided; repeated here
        // because this precondition is only meaningful under it.
        if (pc.getCustomerId() != null) customerRepository.findByIdForUpdate(pc.getCustomerId());
        Long live = switch (pc.getTargetType()) {
            case INVOICE -> invoiceRepository.findByIdForUpdate(pc.getTargetId())
                    .map(Invoice::getVersion).orElse(null);
            // Under the row's OWN write lock, not merely under the customer's. The customer lock
            // does not serialise these: PaymentService.update, every PaymentPromiseService
            // mutator and the overdue sweep write a payment or a promise without ever taking it,
            // so an unlocked read here would be a check-then-act — the version read, the row
            // moved by one of those, and the replay then failing on the optimistic lock with the
            // wrong sentence. Locking them adds no new order, because this is the only path in
            // the application that locks either row and it does so after the customer (PPD-01, B2).
            case PAYMENT -> lockedVersion(Payment.class, pc.getTargetId(), Payment::getVersion);
            case PROMISE -> lockedVersion(PaymentPromise.class, pc.getTargetId(),
                    PaymentPromise::getVersion);
            case CUSTOMER -> customerRepository.findById(pc.getTargetId())
                    .map(Customer::getVersion).orElse(null);
            // Unreachable: returned above. Here so the switch stays exhaustive and a fifteenth
            // target type cannot be added without somebody deciding what it means (B2).
            case DISPUTE, REGION -> pc.getTargetVersion();
        };
        if (live == null) throw new StaleChangeException("The record this change was for no longer exists");
        if (pc.getTargetVersion() != null && !live.equals(pc.getTargetVersion())) {
            // The same precondition InvoiceService.update applies to a stale save (UI-09, B2).
            throw new StaleChangeException(
                    "This record changed while the change was waiting; reload it and raise the change again");
        }
    }

    /** The target's live row version, read under that row's own write lock, or null if it has
     *  gone. A missing row is not an error here: the caller turns it into the sentence that says
     *  the record this change was for is no longer there (B2). */
    private <T> Long lockedVersion(Class<T> type, Long id, Function<T, Long> version) {
        T row = entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        return row == null ? null : version.apply(row);
    }

    private ApprovalDtos.Accepted accepted(PendingChange c, String path, String message) {
        return new ApprovalDtos.Accepted(ApprovalDtos.Accepted.PENDING_APPROVAL, c.getId(),
                c.getAction(), c.getTargetType(), c.getTargetId(), c.getCustomerId(),
                c.getRegionId(), regions.regionName(c.getRegionId()), c.getSummary(),
                c.getExposure(), c.getThresholdApplied(), c.getRequestedAt(), path,
                c.getId() == null ? null : "/approvals/" + c.getId(), message);
    }

    /**
     * Two sentences, because there are two reasons to be held and only one of them is about an
     * amount. Telling somebody deleting a customer with no credit that "₹0.00 is above the limit
     * of ₹0.00" is not a rounding error in the wording, it is false (B2).
     *
     * <p>A branch whose name cannot be read is left out of the sentence rather than written as
     * "the null approval limit": RegionLookup.regionName answers null for a region that has gone,
     * and this is the first caller that has to decide what that reads like (B2, B1).
     */
    private String message(PendingChange c) {
        String region = regions.regionName(c.getRegionId());
        if (c.isAlwaysChecked()) {
            return "This change always needs a second pair of eyes, whatever the amount. It has"
                    + " been sent for approval" + (region == null ? "" : " in " + region)
                    + " and has not taken effect.";
        }
        return Money.format(c.getExposure()) + " is above the"
                + (region == null ? "" : " " + region) + " approval limit of "
                + Money.format(c.getThresholdApplied())
                + ". It has been sent for approval and has not taken effect.";
    }

    // AuditLog.entityId is nullable=false, so the row is anchored on whatever the change is
    // actually about: the target, else the account it will belong to, else nothing at all (B2).
    // Package-private rather than private because PendingChangeCascade writes CHANGE_SUPERSEDED
    // rows that have to land in exactly the same place as the CHANGE_REQUESTED row above them,
    // and a second copy of this rule would be a second chance to disagree with it (B2).
    static String anchorType(PendingChange c) {
        return anchorType(c, null);
    }

    static Long anchorId(PendingChange c) {
        return anchorId(c, null);
    }

    /**
     * The same anchor, once the replay has produced the record the change was about. A create has
     * no target until it happens, so its CHANGE_APPROVED row is the one chance to put the approval
     * into the new record's own history rather than only onto the account (B2).
     */
    private static String anchorType(PendingChange c, Object made) {
        return c.getTargetId() != null || idOf(made) != null
                ? c.getTargetType().name()
                : PendingTargetType.CUSTOMER.name();
    }

    private static Long anchorId(PendingChange c, Object made) {
        if (c.getTargetId() != null) return c.getTargetId();
        Long created = idOf(made);
        if (created != null) return created;
        return c.getCustomerId() != null ? c.getCustomerId() : NO_ANCHOR;
    }

    // instanceof and not a switch pattern: this codebase is on Java 17 and pattern matching for
    // switch is Java 21. Only the three creating actions can land here at all (B2).
    private static Long idOf(Object made) {
        if (made instanceof InvoiceDtos.InvoiceDto d) return d.id();
        if (made instanceof PaymentDtos.PaymentDto d) return d.id();
        if (made instanceof PromiseDtos.PromiseDto d) return d.id();
        return null;
    }
}
