package com.geneinvoice.assignee;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.FilterSpec;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.task.TaskEntityType;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * "Assigned to these people" as a query predicate over a task, a dispute or a promise (§5.9.3): a current assignee
 * names one of them, or is a role one of them holds on the record: a seat on its customer (customer level), or the
 * record's own POC (record level). As of a date the assignees are those of that day (their interval), and the seats
 * and records are those of that day too.
 */
public final class AssignedTo {

    public static final String TASK = "TASK";
    public static final String DISPUTE = "DISPUTE";
    public static final String PROMISE = "PROMISE";

    private AssignedTo() {}

    public static Predicate predicate(String ownerType, Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                      Long userId) {
        return predicate(ownerType, root, q, cb, List.of(userId));
    }

    public static Predicate predicate(String ownerType, Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                      Collection<Long> userIds) {
        return cb.exists(assignees(ownerType, root, q, cb, (a, sq) -> cb.or(
                cb.and(cb.equal(a.get("kind"), RecordAssignee.USER), a.get("userId").in(userIds)),
                cb.and(cb.equal(a.get("kind"), RecordAssignee.ROLE),
                        cb.equal(a.get("roleLevel"), RoleLevel.CUSTOMER.name()),
                        cb.or(seatRole(a, EmailRole.COLLECTION_POC, PocType.COLLECTION, ownerType, root, sq, cb, userIds),
                                seatRole(a, EmailRole.CUSTOMER_SUCCESS_POC, PocType.SUCCESS, ownerType, root, sq, cb,
                                        userIds))),
                cb.and(cb.equal(a.get("kind"), RecordAssignee.ROLE),
                        cb.equal(a.get("roleLevel"), RoleLevel.RECORD.name()),
                        recordRole(a, ownerType, root, sq, cb, userIds)))));
    }

    /**
     * The {@code assigneeUserId} list column: EQ and IN name people; IS_EMPTY and IS_NOT_EMPTY ask whether anyone
     * is assigned at all.
     */
    public static Predicate filter(String ownerType, FilterSpec spec, Root<?> root, CriteriaQuery<?> q,
                                   CriteriaBuilder cb) {
        return switch (spec.operator()) {
            case EQ -> predicate(ownerType, root, q, cb, List.of(asLong(spec.first())));
            case IN -> predicate(ownerType, root, q, cb, spec.values().stream().map(AssignedTo::asLong).toList());
            case IS_EMPTY -> cb.not(cb.exists(assignees(ownerType, root, q, cb, (a, sq) -> null)));
            case IS_NOT_EMPTY -> cb.exists(assignees(ownerType, root, q, cb, (a, sq) -> null));
            default -> throw new BadRequestException(
                    "Operator " + spec.operator().wire() + " is not valid for the assignee column");
        };
    }

    @FunctionalInterface
    private interface Who {
        Predicate of(Root<RecordAssignee> a, Subquery<Long> sq);
    }

    /** The owner's assignees current at the bound point (now when none), narrowed by {@code who} when given. */
    private static Subquery<Long> assignees(String ownerType, Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb,
                                            Who who) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<RecordAssignee> a = sq.from(RecordAssignee.class);
        Predicate owner = cb.and(cb.equal(a.get("ownerType"), ownerType), cb.equal(a.get("ownerId"), root.get("id")));
        AsOfPoint point = AsOf.current();
        Predicate current;
        if (point == null) {
            current = cb.isNull(a.get("removedAt"));
        } else {
            Instant p = point.instant();
            current = cb.and(cb.lessThanOrEqualTo(a.<Instant>get("addedAt"), p),
                    cb.or(cb.isNull(a.get("removedAt")), cb.greaterThan(a.<Instant>get("removedAt"), p)));
        }
        Predicate narrowed = who.of(a, sq);
        sq.select(cb.literal(1L)).where(narrowed == null ? cb.and(owner, current) : cb.and(owner, current, narrowed));
        return sq;
    }

    /** A customer-level role: one of the people holds that seat on the record's customer. */
    private static Predicate seatRole(Root<RecordAssignee> a, EmailRole role, PocType type, String ownerType,
                                      Root<?> root, CommonAbstractCriteria parent, CriteriaBuilder cb,
                                      Collection<Long> userIds) {
        Subquery<Long> seat = Temporal.subquery(parent, Long.class, CustomerPoc.class, cb, (s, sq, where) -> {
            where.add(cb.equal(s.get("customer").get("id"), customerId(ownerType, root)));
            where.add(s.get("user").get("id").in(userIds));
            where.add(cb.equal(s.get("pocType"), type));
            return cb.literal(1L);
        });
        return cb.and(cb.equal(a.get("role"), role.name()), cb.exists(seat));
    }

    /** A record-level role: one of the people is the record's own POC of that role. */
    private static Predicate recordRole(Root<RecordAssignee> a, String ownerType, Root<?> root,
                                        CommonAbstractCriteria parent, CriteriaBuilder cb, Collection<Long> userIds) {
        return switch (ownerType) {
            case TASK -> cb.or(
                    cb.and(cb.equal(a.get("role"), EmailRole.SALES_POC.name()),
                            cb.equal(root.get("entityType"), TaskEntityType.INVOICE),
                            ownsInvoice(root.get("entityId"), parent, cb, userIds)),
                    cb.and(cb.equal(a.get("role"), EmailRole.COLLECTION_POC.name()),
                            cb.equal(root.get("entityType"), TaskEntityType.PAYMENT),
                            collectsPayment(root.get("entityId"), parent, cb, userIds)));
            case DISPUTE -> cb.or(
                    cb.and(cb.equal(a.get("role"), EmailRole.SALES_POC.name()),
                            cb.equal(root.get("targetType"), DisputeTargetType.INVOICE),
                            ownsInvoice(root.get("targetId"), parent, cb, userIds)),
                    cb.and(cb.equal(a.get("role"), EmailRole.COLLECTION_POC.name()),
                            cb.equal(root.get("targetType"), DisputeTargetType.PAYMENT),
                            collectsPayment(root.get("targetId"), parent, cb, userIds)));
            case PROMISE -> cb.and(cb.equal(a.get("role"), EmailRole.COLLECTION_POC.name()),
                    root.get("collectionPoc").get("id").in(userIds));
            default -> throw new IllegalArgumentException("No assignees on " + ownerType);
        };
    }

    private static Predicate ownsInvoice(Expression<?> invoiceId, CommonAbstractCriteria parent, CriteriaBuilder cb,
                                         Collection<Long> userIds) {
        return cb.exists(Temporal.subquery(parent, Long.class, Invoice.class, cb, (inv, sq, where) -> {
            where.add(cb.equal(inv.get("id"), invoiceId));
            where.add(inv.get("salesPoc").get("id").in(userIds));
            return cb.literal(1L);
        }));
    }

    private static Predicate collectsPayment(Expression<?> paymentId, CommonAbstractCriteria parent,
                                             CriteriaBuilder cb, Collection<Long> userIds) {
        return cb.exists(Temporal.subquery(parent, Long.class, Payment.class, cb, (pay, sq, where) -> {
            where.add(cb.equal(pay.get("id"), paymentId));
            where.add(pay.get("collectionPoc").get("id").in(userIds));
            return cb.literal(1L);
        }));
    }

    /** The owner's customer: a plain id on a task and a dispute, an association on a promise. */
    private static Expression<?> customerId(String ownerType, Root<?> root) {
        return PROMISE.equals(ownerType) ? root.get("customer").get("id") : root.get("customerId");
    }

    private static Long asLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException("Expected a user id but got: " + raw);
        }
    }
}
