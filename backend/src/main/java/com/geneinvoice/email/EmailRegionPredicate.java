package com.geneinvoice.email;

import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Which regions am I asking about" for emails (B1, §5.6.7). An email has no region of its own: it takes the
 * region of the record it is about. Emails about a user, a role or a product belong to no region, so they
 * are kept in every answer. An email about a record since deleted takes the region that record's history last
 * named, or the default region when its history names none (as the audit trail answers for a deleted customer).
 */
public final class EmailRegionPredicate {

    private EmailRegionPredicate() {}

    /** The kinds of record that have a region, and their entities. */
    static final Map<EmailEntityType, Class<?>> REGIONAL = new EnumMap<>(Map.of(
            EmailEntityType.CUSTOMER, Customer.class,
            EmailEntityType.INVOICE, Invoice.class,
            EmailEntityType.PAYMENT, Payment.class,
            EmailEntityType.PROMISE, PaymentPromise.class,
            EmailEntityType.DISPUTE, Dispute.class));

    /** Each regional kind's {@code AuditLog.entityType}, where a deleted record's last region is read from. */
    static final Map<EmailEntityType, String> AUDIT_ENTITY = new EnumMap<>(Map.of(
            EmailEntityType.CUSTOMER, CustomerService.ENTITY,
            EmailEntityType.INVOICE, InvoiceService.ENTITY,
            EmailEntityType.PAYMENT, PaymentService.ENTITY,
            EmailEntityType.PROMISE, PaymentPromiseService.ENTITY,
            EmailEntityType.DISPUTE, DisputeService.ENTITY));

    /**
     * For a root of {@link EmailRecipient}: its email is about a record in one of these regions, or about none.
     * {@code defaultRegionId} is the region of a deleted record whose history names none.
     */
    public static PredicateFactory onRecipient(Collection<Long> regionIds, Long defaultRegionId) {
        List<Long> regions = List.copyOf(regionIds);
        return (root, q, cb) -> of(root.get("email"), q, cb, regions, defaultRegionId);
    }

    /** {@code email} is a path to an {@link Email}. An empty set of regions keeps only the emails about no region. */
    public static Predicate of(Path<?> email, CriteriaQuery<?> q, CriteriaBuilder cb, Collection<Long> regionIds,
                               Long defaultRegionId) {
        Path<EmailEntityType> type = email.get("entityType");
        Path<Long> entityId = email.get("entityId");
        List<Predicate> any = new ArrayList<>();
        any.add(cb.not(type.in(REGIONAL.keySet())));
        if (!regionIds.isEmpty()) {
            boolean defaultAsked = defaultRegionId != null && regionIds.contains(defaultRegionId);
            REGIONAL.forEach((kind, entity) -> {
                String auditEntity = AUDIT_ENTITY.get(kind);
                any.add(cb.and(cb.equal(type, kind), entityId.in(idsIn(entity, q, cb, regionIds))));
                // The record is gone: its region is the one its history last named, else the default region.
                Predicate gone = cb.not(cb.exists(idIs(entity, entityId, q, cb)));
                any.add(cb.and(cb.equal(type, kind), gone,
                        cb.exists(lastRegionIn(auditEntity, entityId, q, cb, regionIds))));
                if (defaultAsked) {
                    any.add(cb.and(cb.equal(type, kind), gone,
                            cb.not(cb.exists(regionNamed(auditEntity, entityId, q, cb)))));
                }
            });
        }
        return cb.or(any.toArray(new Predicate[0]));
    }

    private static <T> Subquery<Long> idIs(Class<T> entity, Path<Long> entityId, CriteriaQuery<?> q,
                                           CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<T> record = sq.from(entity);
        sq.select(record.get("id")).where(cb.equal(record.get("id"), entityId));
        return sq;
    }

    /** The record's newest audit row that names a region, when it names one of these. */
    private static Subquery<Long> lastRegionIn(String auditEntity, Path<Long> entityId, CriteriaQuery<?> q,
                                               CriteriaBuilder cb, Collection<Long> regionIds) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<AuditLog> row = sq.from(AuditLog.class);
        Subquery<Long> newest = sq.subquery(Long.class);
        Root<AuditLog> later = newest.from(AuditLog.class);
        newest.select(cb.max(later.get("id"))).where(
                cb.equal(later.get("entityType"), auditEntity),
                cb.equal(later.get("entityId"), entityId),
                cb.isNotNull(later.get("regionId")));
        sq.select(row.get("id")).where(
                cb.equal(row.get("id"), newest),
                row.get("regionId").in(regionIds));
        return sq;
    }

    /** Any audit row of this record that names a region. */
    private static Subquery<Long> regionNamed(String auditEntity, Path<Long> entityId, CriteriaQuery<?> q,
                                              CriteriaBuilder cb) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<AuditLog> row = sq.from(AuditLog.class);
        sq.select(row.get("id")).where(
                cb.equal(row.get("entityType"), auditEntity),
                cb.equal(row.get("entityId"), entityId),
                cb.isNotNull(row.get("regionId")));
        return sq;
    }

    private static <T> Subquery<Long> idsIn(Class<T> entity, CriteriaQuery<?> q, CriteriaBuilder cb,
                                            Collection<Long> regionIds) {
        Subquery<Long> sq = q.subquery(Long.class);
        Root<T> record = sq.from(entity);
        sq.select(record.get("id")).where(record.get("region").get("id").in(regionIds));
        return sq;
    }
}
