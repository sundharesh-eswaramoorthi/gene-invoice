package com.geneinvoice.approval;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Who may see a pending change (§5.11.9). The region the change was asked in governs four paths only: its maker while
 * they still hold that region (SC-15), the region's approvers, the region's threshold managers for a change of its
 * limits, and the POC a create names. Everyone else sees it through the record it is about (for a create: the
 * customer), judged in the region that record is in now, or as of a date the region it was in then (RGN-R4-1,
 * RGN-R5-1): a moved customer's changes leave the old region's plain readers and reach the new region's. A customer
 * login sees changes to its own customer's existing records, never one an internal dispute raised (SC-1), and no
 * list. Asked as of a date, a change is there while it was outstanding: requested by then and not yet decided (D8).
 * One not visible is missing (404).
 */
@Component
public class PendingChangeScope {

    static final String NOT_FOUND = "Pending change not found";

    /** Which changes a list is about: all the caller may see, those waiting for them, or their own requests. */
    public enum View {
        ALL, AWAITING_ME, MINE;

        public static View parse(String raw) {
            if (raw == null || raw.isBlank()) return ALL;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("view must be one of ALL, AWAITING_ME or MINE, not " + raw);
            }
        }
    }

    /** The records a change on an existing record can be about, and the privilege that sees each kind. */
    private record RecordKind(String entityType, Class<?> type, String viewPrivilege, ScopeResolver.Kind kind) {}

    private static final List<RecordKind> RECORD_KINDS = List.of(
            new RecordKind("INVOICE", Invoice.class, Privileges.INVOICE_VIEW, ScopeResolver.Kind.INVOICE),
            new RecordKind("PAYMENT", Payment.class, Privileges.PAYMENT_VIEW, ScopeResolver.Kind.PAYMENT),
            new RecordKind("PROMISE", PaymentPromise.class, Privileges.PROMISE_VIEW, ScopeResolver.Kind.PROMISE),
            new RecordKind("DISPUTE", Dispute.class, Privileges.DISPUTE_VIEW, ScopeResolver.Kind.DISPUTE));

    @PersistenceContext
    private EntityManager em;

    private final PendingChangeRepository pending;
    private final CurrentUser currentUser;
    private final ScopeResolver scopeResolver;

    public PendingChangeScope(PendingChangeRepository pending, CurrentUser currentUser, ScopeResolver scopeResolver) {
        this.pending = pending;
        this.currentUser = currentUser;
        this.scopeResolver = scopeResolver;
    }

    public PendingChange requireVisible(long id) {
        PendingChange pc = pending.findById(id).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        requireVisible(pc);
        return pc;
    }

    public void requireVisible(PendingChange pc) {
        if (!isVisible(pc)) throw new NotFoundException(NOT_FOUND);
    }

    public boolean isVisible(PendingChange pc) {
        if (AsOf.active() && pc.getRequestedAt().isAfter(AsOf.current().instant())) return false;
        User me = currentUser.require();
        Long region = pc.getRegionId();
        if (me.getCustomerId() != null) {
            return me.getCustomerId().equals(pc.getCustomerId()) && pc.getEntityId() != null && !pc.isInternalSource()
                    && canSeeRecord(pc);
        }
        RegionRights rights = currentUser.rights();
        if (Objects.equals(pc.getMakerUserId(), me.getId()) && rights.grantedRegions().contains(region)) return true;
        if (rights.has(Privileges.CHANGE_APPROVE, region)) return true;
        if (pc.getOperation() == ApprovalOperation.THRESHOLD_CHANGE) {
            return rights.has(Privileges.APPROVAL_THRESHOLD_MANAGE, region);
        }
        String privilege = pc.getOperation().viewPrivilege();
        // Seeing the record is judged in the region the record is in (as of a date, the one it was in then), not the
        // one the change was asked in: a moved customer's records take their changes to the new region (RGN-R5-1).
        if (pc.getEntityId() != null) {
            ScopeResolver.Kind kind = ScopeResolver.Kind.valueOf(pc.getEntityType());
            Long at = recordRegion(kind, pc.getEntityId(), region);
            return rights.has(privilege, at) && visible(kind, pc.getEntityId(), at);
        }
        if (rights.has(privilege, region) && Objects.equals(pc.getRecordPocUserId(), me.getId())) return true;
        Long at = recordRegion(ScopeResolver.Kind.CUSTOMER, pc.getCustomerId(), region);
        return rights.has(privilege, at) && visible(ScopeResolver.Kind.CUSTOMER, pc.getCustomerId(), at);
    }

    /** Whether the caller can open the record the change is about; asks, never throws. */
    public boolean canSeeRecord(PendingChange pc) {
        if (pc.getOperation() == ApprovalOperation.THRESHOLD_CHANGE) {
            // A region's limits are seen by those who approve or set them there.
            RegionRights rights = currentUser.rights();
            return !currentUser.isCustomer() && (rights.has(Privileges.CHANGE_APPROVE, pc.getRegionId())
                    || rights.has(Privileges.APPROVAL_THRESHOLD_MANAGE, pc.getRegionId()));
        }
        // The record's own region (as of a date, the one it was in then), not the one the change was asked in:
        // a customer that has moved since takes its records out of that region's sight (RGN-R4-1).
        if (pc.getEntityId() == null) {
            return visible(ScopeResolver.Kind.CUSTOMER, pc.getCustomerId(),
                    recordRegion(ScopeResolver.Kind.CUSTOMER, pc.getCustomerId(), pc.getRegionId()));
        }
        ScopeResolver.Kind kind = ScopeResolver.Kind.valueOf(pc.getEntityType());
        return visible(kind, pc.getEntityId(), recordRegion(kind, pc.getEntityId(), pc.getRegionId()));
    }

    /**
     * The region the record is in, as of a date the one it was in then: one scalar query over the live table or its
     * versions (which carry the record's id and region alike), so no versioned entity is loaded. A record that does
     * not exist (deleted, or not yet made at the point asked about) answers {@code fallback}, the change's region.
     * Never {@code getSingleResult}: its exception would mark a read-only transaction rollback-only.
     */
    private Long recordRegion(ScopeResolver.Kind kind, Long id, Long fallback) {
        if (id == null) return fallback;
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        List<Predicate> where = new ArrayList<>();
        Root<?> root = Temporal.fromValid(cq, kind.type(), cb, where);
        where.add(cb.equal(root.get("id"), id));
        cq.select(root.get("region").<Long>get("id")).where(where.toArray(new Predicate[0]));
        List<Long> found = em.createQuery(cq).setMaxResults(1).getResultList();
        return found.isEmpty() || found.get(0) == null ? fallback : found.get(0);
    }

    /** {@link ScopeResolver#requireVisible} is not transactional, so its refusal can be caught here safely. */
    private boolean visible(ScopeResolver.Kind kind, Long id, Long region) {
        if (id == null) return false;
        try {
            scopeResolver.requireVisible(kind, id, region, NOT_FOUND);
            return true;
        } catch (NotFoundException hidden) {
            return false;
        }
    }

    /**
     * The changes a staff member's list, summary and count are made of (§5.11.9): everything they may see, in the
     * regions asked about ({@code regionIds}, never {@code lockedFilters}, R14), narrowed to the view, and as of a
     * date the changes outstanding then. "In the regions asked about" means, on the maker, approver, limit-setter and
     * named-POC paths, the region the change was asked in; on the record paths, the region the record is in (as of a
     * date, the one it was in then), through the record subqueries, which are built on the outer query as the
     * dashboard's are. The {@code regionId} column and a row's region still name the region the change was asked in.
     * A customer login has no list (403).
     */
    public ScopeResolver.Scope forList(View view) {
        User me = currentUser.require();
        if (me.getCustomerId() != null) throw new AccessDeniedException("Not allowed");
        RegionRights rights = currentUser.rights();
        Long meId = me.getId();
        Set<Long> granted = rights.grantedRegions();
        Set<Long> asked = ViewScopeHolder.current().narrow(granted);
        ScopeResolver.RegionCoverage coverage = new ScopeResolver.RegionCoverage(asked, Set.of(), false);
        if (asked.isEmpty()) {
            return new ScopeResolver.Scope(List.of((root, q, cb) -> cb.disjunction()), List.of(), coverage);
        }
        List<Long> askedIds = List.copyOf(asked);
        List<Long> grantedIds = List.copyOf(granted);
        List<Long> approving = List.copyOf(rights.regionsWith(Privileges.CHANGE_APPROVE));
        List<Long> settingLimits = List.copyOf(rights.regionsWith(Privileges.APPROVAL_THRESHOLD_MANAGE));

        List<RecordKind> kinds = new ArrayList<>();
        List<List<Long>> kindRegions = new ArrayList<>();
        List<ScopeResolver.Scope> kindScopes = new ArrayList<>();
        for (RecordKind k : RECORD_KINDS) {
            Set<Long> regions = rights.regionsWith(k.viewPrivilege());
            if (regions.isEmpty()) continue;
            kinds.add(k);
            kindRegions.add(List.copyOf(regions));
            kindScopes.add(scopeResolver.scope(k.kind(), ScopeResolver.Access.VIEW, ScopeResolver.Reach.SELECTED));
        }
        List<ApprovalOperation> creates = new ArrayList<>();
        List<List<Long>> createRegions = new ArrayList<>();
        for (ApprovalOperation op : ApprovalOperation.values()) {
            if (!op.isCreate()) continue;
            Set<Long> regions = rights.regionsWith(op.viewPrivilege());
            if (regions.isEmpty()) continue;
            creates.add(op);
            createRegions.add(List.copyOf(regions));
        }
        ScopeResolver.Scope customers = creates.isEmpty() ? null : scopeResolver.forCustomers();

        PredicateFactory visible = (root, q, cb) -> {
            Path<Long> region = root.get("region").get("id");
            // Paths the change's own region governs: the maker's, the approver's, the limit setter's, a create's POC.
            List<Predicate> byChange = new ArrayList<>();
            if (!grantedIds.isEmpty()) {
                byChange.add(cb.and(cb.equal(root.get("makerUserId"), meId), region.in(grantedIds)));
            }
            if (!approving.isEmpty()) byChange.add(region.in(approving));
            if (!settingLimits.isEmpty()) {
                byChange.add(cb.and(cb.equal(root.get("operation"), ApprovalOperation.THRESHOLD_CHANGE),
                        region.in(settingLimits)));
            }
            if (!creates.isEmpty()) {
                List<Predicate> ops = new ArrayList<>();
                for (int i = 0; i < creates.size(); i++) {
                    ops.add(cb.and(cb.equal(root.get("operation"), creates.get(i)), region.in(createRegions.get(i))));
                }
                byChange.add(cb.and(cb.isNull(root.get("entityId")), cb.equal(root.get("recordPocUserId"), meId),
                        cb.or(ops.toArray(new Predicate[0]))));
            }
            List<Predicate> any = new ArrayList<>();
            if (!byChange.isEmpty()) {
                any.add(cb.and(region.in(askedIds), cb.or(byChange.toArray(new Predicate[0]))));
            }
            // Paths the record governs, in the region it is in (as of a date, the one it was in then): no condition on
            // the change's region. The SELECTED scopes are already narrowed to the regions asked about.
            for (int i = 0; i < kinds.size(); i++) {
                RecordKind k = kinds.get(i);
                any.add(cb.and(cb.equal(root.get("entityType"), k.entityType()), cb.isNotNull(root.get("entityId")),
                        root.get("entityId").in(ids(k.type(), kindScopes.get(i), inRegions(kindRegions.get(i)),
                                q, cb))));
            }
            for (int i = 0; i < creates.size(); i++) {
                any.add(cb.and(cb.isNull(root.get("entityId")), cb.equal(root.get("operation"), creates.get(i)),
                        root.get("customerId").in(ids(Customer.class, customers, inRegions(createRegions.get(i)),
                                q, cb))));
            }
            return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(new Predicate[0]));
        };
        List<PredicateFactory> predicates = new ArrayList<>();
        predicates.add(visible);
        switch (view) {
            case AWAITING_ME -> predicates.add((root, q, cb) -> approving.isEmpty() ? cb.disjunction() : cb.and(
                    outstanding(root, cb), cb.notEqual(root.get("makerUserId"), meId),
                    root.get("region").get("id").in(approving)));
            case MINE -> predicates.add((root, q, cb) -> cb.equal(root.get("makerUserId"), meId));
            case ALL -> { }
        }
        if (AsOf.active()) predicates.add((root, q, cb) -> outstanding(root, cb));
        return new ScopeResolver.Scope(predicates, List.of(), coverage);
    }

    /**
     * The ids of the records in {@code scope} that {@code extra} also admits, a subquery of the outer query (as of a
     * date, as they were then). The scope's own list is never changed.
     */
    private static Subquery<Long> ids(Class<?> type, ScopeResolver.Scope scope, PredicateFactory extra,
                                      CriteriaQuery<?> q, CriteriaBuilder cb) {
        return Temporal.subquery(q, Long.class, type, cb, (record, sq, where) -> {
            for (PredicateFactory f : scope.predicates()) {
                Predicate p = f.build(record, q, cb);
                if (p != null) where.add(p);
            }
            Predicate p = extra.build(record, q, cb);
            if (p != null) where.add(p);
            return record.get("id");
        });
    }

    /** The record (live or a version of it) is in one of these regions. */
    private static PredicateFactory inRegions(List<Long> regionIds) {
        return (record, q, cb) -> record.get("region").get("id").in(regionIds);
    }

    /**
     * The changes still waiting on a record (§6.11 {@code for-record}), as of a date those outstanding then, newest
     * first, each one the caller may see. INVOICE, PAYMENT and PROMISE are the record whose money moves (a dispute's
     * approval included); DISPUTE is the dispute itself; CUSTOMER is the customer's waiting creates.
     */
    public List<PendingChange> forRecord(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<PendingChange> cq = cb.createQuery(PendingChange.class);
        Root<PendingChange> root = cq.from(PendingChange.class);
        Predicate on = switch (entityType) {
            case "DISPUTE" -> cb.and(cb.equal(root.get("entityType"), entityType),
                    cb.equal(root.get("entityId"), entityId));
            case "CUSTOMER" -> cb.and(cb.equal(root.get("customerId"), entityId), cb.isNull(root.get("entityId")));
            default -> cb.and(cb.equal(root.get("affectedType"), entityType), cb.equal(root.get("affectedId"), entityId));
        };
        cq.where(on, outstanding(root, cb));
        cq.orderBy(cb.desc(root.get("requestedAt")), cb.desc(root.get("id")));
        return em.createQuery(cq).getResultList().stream().filter(this::isVisible).toList();
    }

    /**
     * Which of these records have a change waiting on them (the list rows' {@code pendingApproval}, §5.11.9): one
     * query for the page. A dispute is matched as the change's record, anything else as the record whose money moves.
     * As of a date, waiting then; a customer login never counts a change an internal dispute raised.
     */
    public Set<Long> pendingOn(String entityType, Collection<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        boolean asEntity = "DISPUTE".equals(entityType);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<PendingChange> root = cq.from(PendingChange.class);
        Path<Long> id = root.get(asEntity ? "entityId" : "affectedId");
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get(asEntity ? "entityType" : "affectedType"), entityType));
        where.add(id.in(List.copyOf(new HashSet<>(ids))));
        where.add(outstanding(root, cb));
        if (callerIsCustomer()) where.add(cb.isFalse(root.get("internalSource")));
        cq.select(id).distinct(true).where(where.toArray(new Predicate[0]));
        return Set.copyOf(em.createQuery(cq).getResultList());
    }

    /**
     * The list column {@code pendingApproval} (BOOLEAN, {@code eq} only): whether a change waits on the row's
     * record. An EXISTS over {@code pending_changes}, so it works on a version root as of a date too.
     */
    public static Predicate pendingApprovalFilter(String entityType, FilterSpec spec, Root<?> root,
                                                  CriteriaQuery<?> q, CriteriaBuilder cb) {
        boolean asEntity = "DISPUTE".equals(entityType);
        Subquery<Long> sq = q.subquery(Long.class);
        Root<PendingChange> pc = sq.from(PendingChange.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(pc.get(asEntity ? "entityType" : "affectedType"), entityType));
        where.add(cb.equal(pc.get(asEntity ? "entityId" : "affectedId"), root.get("id")));
        where.add(outstanding(pc, cb));
        if (callerIsCustomer()) where.add(cb.isFalse(pc.get("internalSource")));
        sq.select(cb.literal(1L)).where(where.toArray(new Predicate[0]));
        return switch (spec.operator()) {
            case EQ -> asBoolean(spec.first()) ? cb.exists(sq) : cb.not(cb.exists(sq));
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for the pendingApproval column");
        };
    }

    /** Waiting: PENDING now, or as of a date requested by then and not decided until after it (D8). */
    static Predicate outstanding(Root<?> pc, CriteriaBuilder cb) {
        AsOfPoint point = AsOf.current();
        if (point == null) return cb.equal(pc.get("status"), PendingStatus.PENDING);
        Instant p = point.instant();
        return cb.and(cb.lessThanOrEqualTo(pc.<Instant>get("requestedAt"), p),
                cb.or(cb.isNull(pc.get("decidedAt")), cb.greaterThan(pc.<Instant>get("decidedAt"), p)));
    }

    /** A customer login, or nobody signed in at all: either way, no internal work is shown (fails closed). */
    private static boolean callerIsCustomer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return !(auth != null && auth.getPrincipal() instanceof AppUserDetails p)
                || p.getUser().getCustomerId() != null;
    }

    private static boolean asBoolean(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new BadRequestException("Expected true or false but got: " + raw);
    }
}
