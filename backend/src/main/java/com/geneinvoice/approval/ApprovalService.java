package com.geneinvoice.approval;

import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Fit;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The approvals API's reads (§6.11), and deciding a pending change (§5.11.6). Approving re-runs the gated method as the maker in the checker's
 * transaction (TX-A), with the approval bound around that transaction so its audit rows, outbox events and
 * versions all say APPROVAL and name the maker (D4, CF-1). A re-run the rules now refuse is recorded as FAILED in
 * a second transaction (TX-B), re-reading the change rather than reusing anything from the rolled-back one.
 */
@Service
public class ApprovalService {

    public static final String CHANGE_APPROVED = "CHANGE_APPROVED";
    public static final String CHANGE_REJECTED = "CHANGE_REJECTED";
    public static final String CHANGE_WITHDRAWN = "CHANGE_WITHDRAWN";
    public static final String CHANGE_APPLY_FAILED = "CHANGE_APPLY_FAILED";

    private final PendingChangeRepository pending;
    private final PendingChangeScope scope;
    private final ChangeAppliers appliers;
    private final PendingChangeViews views;
    private final PayloadCodec codec;
    private final AuditService auditService;
    private final ApprovalNotifier notifier;
    private final CurrentUser currentUser;
    private final AppUserDetailsService principals;
    private final RunAs runAs;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final TableQueryExecutor queryExecutor;
    private final DisputeRepository disputeRepository;
    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ApprovalService(PendingChangeRepository pending, PendingChangeScope scope, ChangeAppliers appliers,
                           PendingChangeViews views, PayloadCodec codec, AuditService auditService,
                           ApprovalNotifier notifier, CurrentUser currentUser, AppUserDetailsService principals,
                           RunAs runAs, UserRepository userRepository, EntityManager entityManager,
                           TableQueryExecutor queryExecutor, DisputeRepository disputeRepository,
                           AssigneeStore assigneeStore, AssigneeService assigneeService, Clock clock,
                           PlatformTransactionManager txManager) {
        this.pending = pending;
        this.scope = scope;
        this.appliers = appliers;
        this.views = views;
        this.codec = codec;
        this.auditService = auditService;
        this.notifier = notifier;
        this.currentUser = currentUser;
        this.principals = principals;
        this.runAs = runAs;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.queryExecutor = queryExecutor;
        this.disputeRepository = disputeRepository;
        this.assigneeStore = assigneeStore;
        this.assigneeService = assigneeService;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Why an approval did not take: the change becomes FAILED with this reason. */
    private static final class ApplyFailed extends RuntimeException {
        private final String reason;

        ApplyFailed(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** The approvals list's tiles: what is waiting among the changes the caller may see, and how much of it. */
    public record Summary(long pendingCount, BigDecimal pendingAmount, long awaitingMeCount, long mineCount) {}

    public record Count(long count) {}

    @Transactional(readOnly = true)
    public PendingChangeViews.Detail get(long id) {
        return views.detail(scope.requireVisible(id), currentUser.require());
    }

    /** A page of the changes the caller may see, in the view asked for (§6.11); as of a date, those waiting then. */
    @Transactional(readOnly = true)
    public PageResponse<PendingChangeViews.Brief> page(TableQuery query, PendingChangeScope.View view) {
        ScopeResolver.Scope visible = scope.forList(view);
        var page = queryExecutor.run(PendingChange.class, TableSchemas.APPROVALS, query, visible.predicates(),
                List.of("region"));
        return PageResponse.of(page.content().stream().map(views::brief).toList(), query, page.total(), visible);
    }

    /** Over the list's filters: the changes waiting, their amount, those the caller may decide, the caller's own. */
    @Transactional(readOnly = true)
    public Summary summary(TableQuery query) {
        ScopeResolver.Scope visible = scope.forList(PendingChangeScope.View.ALL);
        Long me = currentUser.require().getId();
        List<Long> approving = List.copyOf(currentUser.regionsWith(Privileges.CHANGE_APPROVE));
        List<PredicateFactory> waiting = new ArrayList<>(visible.predicates());
        waiting.add((root, q, cb) -> PendingChangeScope.outstanding(root, cb));
        Object[] row = queryExecutor.aggregate(PendingChange.class, TableSchemas.APPROVALS, query, waiting,
                (root, q, cb) -> {
                    Expression<Integer> one = cb.literal(1);
                    Expression<Integer> none = cb.literal(0);
                    Predicate decidable = approving.isEmpty() ? cb.disjunction() : cb.and(
                            cb.notEqual(root.get("makerUserId"), me), root.get("region").get("id").in(approving));
                    return List.of(cb.count(root), cb.sum(root.<BigDecimal>get("amount")),
                            cb.sum(cb.<Integer>selectCase().when(decidable, one).otherwise(none)),
                            cb.sum(cb.<Integer>selectCase().when(cb.equal(root.get("makerUserId"), me), one)
                                    .otherwise(none)));
                });
        return new Summary(Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asLong(row[2]),
                Aggregates.asLong(row[3]));
    }

    /** The nav badge: changes waiting for the caller to decide, in the regions asked about. */
    @Transactional(readOnly = true)
    public Count awaitingMeCount() {
        ScopeResolver.Scope awaiting = scope.forList(PendingChangeScope.View.AWAITING_ME);
        return new Count(queryExecutor.count(PendingChange.class, TableSchemas.APPROVALS,
                TableQuery.parseUnpaged(TableSchemas.APPROVALS, null, List.of()), awaiting.predicates()));
    }

    /** The changes an export may list: those the caller may see in the view, in the list's order. */
    @Transactional(readOnly = true)
    public List<PendingChangeViews.Brief> briefs(TableQuery query, PendingChangeScope.View view, List<Long> only) {
        List<Long> ids = queryExecutor.ids(PendingChange.class, TableSchemas.APPROVALS, query,
                scope.forList(view).predicates(), TableQueryExecutor.BULK_ID_LIMIT);
        if (only != null) ids = ids.stream().filter(only::contains).toList();
        Map<Long, PendingChange> byId = new HashMap<>();
        pending.findAllById(ids).forEach(pc -> byId.put(pc.getId(), pc));
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(views::brief).toList();
    }

    /** What waits on one record the caller can see (§6.11 {@code for-record}); the record was checked first. */
    @Transactional(readOnly = true)
    public List<PendingChangeViews.Brief> forRecord(String entityType, Long entityId) {
        return scope.forRecord(entityType, entityId).stream().map(views::brief).toList();
    }

    /**
     * Applies the change as its maker. Runs outside any transaction: TX-A must commit while the approval origin
     * is still bound, and a failure must be written in a transaction of its own.
     */
    public PendingChangeViews.Detail approve(long id, String note) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An approval runs its own transactions");
        }
        User checker = currentUser.require();
        Long makerId = pending.findMakerId(id).orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
        String fitted = Fit.to(blankToNull(note), PendingChange.NOTE_MAX);
        try {
            return WriteOrigins.callWith(WriteOrigin.approval(id, checker.getId()).withActor(makerId),
                    () -> inTransaction(() -> applyInTransaction(id, checker, fitted)));
        } catch (ApplyFailed failed) {
            // The origin binding is restored by now; the failure is the checker's own write.
            return tx.execute(status -> {
                PendingChange pc = pending.findByIdForUpdate(id)
                        .orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
                if (pc.isPending()) {
                    pc.fail(checker.getId(), failed.reason, clock.instant());
                    auditService.recordLinked(auditType(pc), auditId(pc), CHANGE_APPLY_FAILED, null, null,
                            checker.getId(), disputeId(pc), pc.getId(), failed.reason);
                    notifier.decided(pc);
                }
                return views.detail(pc, checker);
            });
        }
    }

    /**
     * TX-A. When the connection is lost with the failure (a pool drops one whose lock wait timed out), the
     * rollback cannot be sent, and its own error would replace the failure; nothing was committed either way. The
     * caller is told what failed, so a lock held elsewhere stays a 409 and the change stays pending.
     */
    private <T> T inTransaction(Supplier<T> body) {
        RuntimeException[] failed = new RuntimeException[1];
        try {
            return tx.execute(status -> {
                try {
                    return body.get();
                } catch (RuntimeException e) {
                    failed[0] = e;
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            if (failed[0] != null && failed[0] != e) throw failed[0];
            throw e;
        }
    }

    private PendingChangeViews.Detail applyInTransaction(long id, User checker, String note) {
        PendingChange peek = pending.findById(id)
                .orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
        ChangeApplier applier = appliers.of(peek.getOperation());
        applier.lockDomainRows(peek);
        // Let go of the unlocked copy, so the locked read below returns the row as it is now, not as it was.
        entityManager.detach(peek);
        PendingChange pc = pending.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
        requireDecidable(pc, checker);
        if (Objects.equals(pc.getMakerUserId(), checker.getId())) {
            throw new BadRequestException("You cannot approve a change you requested");
        }
        requireNotTheRulesOwner(pc, checker);
        requireApprover(pc);

        Outcome<?> out;
        try {
            if (!Objects.equals(pc.getRegionId(), applier.currentRegionId(pc))) {
                throw new ChangeConflictException("The record has moved to another region");
            }
            AppUserDetails maker = principals.principalFor(pc.getMakerUserId())
                    .filter(p -> p.getUser().isActive())
                    .orElseThrow(() -> new RunAs.InactiveUserException(
                            "User #" + pc.getMakerUserId() + " cannot act now"));
            WriteOrigin origin = WriteOrigin.approval(pc.getId(), checker.getId()).withActor(pc.getMakerUserId());
            ApprovalContext.Applying applying = new ApprovalContext.Applying(pc.getId(), checker.getId(),
                    pc.getOperation(), pc.outstandingKeyAtRequest(), pc.getCustomerId(), pc.getAmount(),
                    pc.getRequestedAt());
            out = runAs.call(maker, ViewScope.LIVE_ALL, origin,
                    () -> ApprovalContext.applying(applying, () -> applier.apply(pc)));
        } catch (RuntimeException e) {
            // Everything the maker's re-run can be refused with; the transaction is rolled back and TX-B records
            // the reason. Anything else (a conflict on commit, a bug) propagates as it is.
            String reason = failureReason(e);
            if (reason != null) throw new ApplyFailed(reason);
            throw e;
        }
        Object value = out.appliedOrThrow();
        pc.approve(checker.getId(), note, clock.instant(), applier.resultEntityId(value),
                codec.toJson(applier.resultView(value)));
        auditService.recordLinked(auditType(pc), auditId(pc), CHANGE_APPROVED, null, null, checker.getId(),
                disputeId(pc), pc.getId(), note);
        notifier.decided(pc);
        assigneeNotifications(pc, applier.resultEntityId(value));
        return views.detail(pc, checker);
    }

    /**
     * Check 5a: a change held from an internal dispute is never approved by the owner of the rule that raised the
     * dispute, who proposed it (SC-10). Neither of the two pairs of eyes may be the rule's.
     */
    private void requireNotTheRulesOwner(PendingChange pc, User checker) {
        Long disputeId = disputeId(pc);
        if (!pc.isInternalSource() || disputeId == null) return;
        if (disputeRepository.openerOf(disputeId).filter(checker.getId()::equals).isPresent()) {
            throw new BadRequestException("A change your own rule proposed must be approved by someone else");
        }
    }

    /**
     * An approved promise made with co-assignees (a rule's, say) assigns them as it is made; they are told now, as
     * they would have been had it not been held (§5.10.12). A rule's owner hears too; a person's own is left out.
     */
    private void assigneeNotifications(PendingChange pc, Long resultId) {
        if (pc.getOperation() != ApprovalOperation.PROMISE_CREATE || resultId == null) return;
        List<EmailToken> assigned = assigneeStore.list(AssignedTo.PROMISE, resultId);
        if (assigned.isEmpty()) return;
        Long except = pc.getOrigin() == ChangeOrigin.AUTOMATION ? null : pc.getMakerUserId();
        assigneeService.notifyAssignedTo(AssignedTo.PROMISE, resultId, assigned, except);
    }

    @Transactional
    public PendingChangeViews.Detail reject(long id, String note) {
        User checker = currentUser.require();
        PendingChange pc = pending.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
        requireDecidable(pc, checker);
        if (Objects.equals(pc.getMakerUserId(), checker.getId())) {
            throw new BadRequestException("Use Withdraw for your own request");
        }
        requireApprover(pc);
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty() || reason.length() > PendingChange.NOTE_MAX) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("note", reason.isEmpty()
                    ? "Say why the change is rejected"
                    : "Keep the note to " + PendingChange.NOTE_MAX + " characters"));
        }
        pc.reject(checker.getId(), reason, clock.instant());
        auditService.recordLinked(auditType(pc), auditId(pc), CHANGE_REJECTED, null, null, checker.getId(),
                disputeId(pc), pc.getId(), reason);
        notifier.decided(pc);
        return views.detail(pc, checker);
    }

    @Transactional
    public PendingChangeViews.Detail withdraw(long id) {
        User maker = currentUser.require();
        PendingChange pc = pending.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(PendingChangeScope.NOT_FOUND));
        scope.requireVisible(pc);
        ViewScopeHolder.requireLive();
        requirePending(pc);
        if (!Objects.equals(pc.getMakerUserId(), maker.getId())) throw new AccessDeniedException("Not allowed");
        pc.withdraw(maker.getId(), clock.instant());
        auditService.recordLinked(auditType(pc), auditId(pc), CHANGE_WITHDRAWN, null, null, maker.getId(),
                disputeId(pc), pc.getId(), null);
        return views.detail(pc, maker);
    }

    /** Checks 1–4 of §5.11.6: visible (404), live, still pending (409), not a customer login (403). */
    private void requireDecidable(PendingChange pc, User caller) {
        scope.requireVisible(pc);
        ViewScopeHolder.requireLive();
        requirePending(pc);
        if (caller.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
    }

    /** Check 6: the approval right in the change's own region. */
    private void requireApprover(PendingChange pc) {
        if (!currentUser.hasIn(Privileges.CHANGE_APPROVE, pc.getRegionId())) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    private void requirePending(PendingChange pc) {
        if (pc.isPending()) return;
        String by = pc.getDecidedByUserId() == null ? "" : " by " + userRepository.findById(pc.getDecidedByUserId())
                .map(MakerChecker::displayName).orElse("user #" + pc.getDecidedByUserId());
        String at = pc.getDecidedAt() == null ? "" : " at " + pc.getDecidedAt();
        throw new ApprovalConflictException("Already " + pc.getStatus().name().toLowerCase(Locale.ROOT) + by + at);
    }

    /** What the maker's re-run was refused with, as the reason shown; null for anything that is not a refusal. */
    static String failureReason(RuntimeException e) {
        if (e instanceof GlobalExceptionHandler.InvalidFieldsException fields) {
            return String.join("; ", new TreeMap<>(fields.getFieldErrors()).values());
        }
        if (e instanceof BadRequestException || e instanceof ChangeConflictException
                || e instanceof PayloadException) {
            return e.getMessage();
        }
        if (e instanceof NotFoundException) return "The requester can no longer reach this record";
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            return "The requester no longer has the right to make this change";
        }
        if (e instanceof RunAs.InactiveUserException) return "The requester's account is no longer active";
        return null;
    }

    static String auditType(PendingChange pc) {
        return Gated.auditEntityType(pc.getOperation(), pc.getAffectedType());
    }

    static Long auditId(PendingChange pc) {
        return Gated.auditEntityId(pc.getOperation(), pc.getCustomerId(), pc.getAffectedId(), pc.getEntityId());
    }

    static Long disputeId(PendingChange pc) {
        return pc.getOperation() == ApprovalOperation.DISPUTE_APPROVE ? pc.getEntityId() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
