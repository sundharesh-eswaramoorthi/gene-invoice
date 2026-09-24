package com.geneinvoice.common.query;

import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
public class TableQueryExecutor {

    public static final int BULK_ID_LIMIT = 5000;

    @PersistenceContext
    private EntityManager em;

    // Constructor-injected on purpose: the region axis is part of what a query IS, so the one
    // object that can answer it has to be present before the executor can run at all (B1).
    private final RegionScope regionScope;

    public TableQueryExecutor(RegionScope regionScope) {
        this.regionScope = regionScope;
    }

    public record Page<T>(List<T> content, long total) {}

    @Transactional(readOnly = true)
    public <T> Page<T> run(Class<T> type, TableSchema schema, TableQuery query,
                           List<PredicateFactory> scope, List<String> fetchAssociations) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<T> cq = cb.createQuery(type);
        Root<T> root = cq.from(type);
        for (String assoc : fetchAssociations) {
            leftJoinFetch(root, assoc);
        }
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));

        TypedQuery<T> tq = em.createQuery(cq);
        if (query.size() != Integer.MAX_VALUE) {
            // A page number far past the end overflows int arithmetic; there is nothing on it
            // anyway, so it is an empty page rather than a 500 (D-40).
            long offset = (long) query.page() * query.size();
            if (offset > Integer.MAX_VALUE) {
                return new Page<>(List.of(), count(type, schema, query, scope));
            }
            tq.setFirstResult((int) offset);
            tq.setMaxResults(query.size());
        } else {
            tq.setMaxResults(BULK_ID_LIMIT);
        }
        List<T> content = tq.getResultList();

        return new Page<>(content, count(type, schema, query, scope));
    }

    @Transactional(readOnly = true)
    public <T> long count(Class<T> type, TableSchema schema, TableQuery query, List<PredicateFactory> scope) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(type);
        countQuery.select(cb.count(countRoot));
        countQuery.where(all(cb, predicates(countRoot, countQuery, cb, schema, query, scope)));
        return em.createQuery(countQuery).getSingleResult();
    }

    @Transactional(readOnly = true)
    public <T> boolean inScope(Class<T> type, TableSchema schema, Long id, List<PredicateFactory> scope) {
        // An empty scope list no longer means "everything": the region axis still applies, so this
        // short-circuits only for the schemas that are deliberately unregioned. Without this one
        // line every requireInBook is a no-op for a SCOPE_OVERRIDE holder, and four of the six
        // seeded roles hold SCOPE_OVERRIDE (B1).
        if (scope.isEmpty() && schema.axis() == RegionAxis.NONE) return true;
        return count(type, schema, TableQuery.parseUnpaged(schema, null, List.of("id:eq:" + id)), scope) > 0;
    }

    @Transactional(readOnly = true)
    public <T> List<Long> ids(Class<T> type, TableSchema schema, TableQuery query,
                              List<PredicateFactory> scope, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<T> root = cq.from(type);
        cq.select(root.get("id"));
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));
        return em.createQuery(cq).setMaxResults(limit).getResultList();
    }

    @Transactional(readOnly = true)
    public <T> Object[] aggregate(Class<T> type, TableSchema schema, TableQuery query,
                                  List<PredicateFactory> scope, AggregateSelections selections) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<T> root = cq.from(type);
        List<Expression<?>> select = selections.build(root, cq, cb);
        cq.multiselect(select.toArray(new Expression<?>[0]));
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        List<Object[]> rows = em.createQuery(cq).getResultList();
        return rows.isEmpty() ? new Object[select.size()] : rows.get(0);
    }

    @FunctionalInterface
    public interface AggregateSelections {
        List<Expression<?>> build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb);
    }

    private <T> List<Predicate> predicates(Root<T> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                           TableSchema schema, TableQuery query,
                                           List<PredicateFactory> scope) {
        List<Predicate> all = new ArrayList<>();
        // The schema and the root must be the same entity, or the region axis would be read off
        // the wrong classification and a mismatched pair would scope itself by somebody else's
        // rule. Nothing enforced this before (B1).
        if (schema.entityType() != root.getJavaType()) {
            throw new IllegalStateException("Schema " + schema.entity() + " used on root "
                    + root.getJavaType().getSimpleName());
        }
        // Mandatory and caller-independent: region is an axis of the query, not a scope argument,
        // so an endpoint written next year cannot omit it by passing List.of() (B1).
        Predicate region = regionScope.predicate(root, cq, cb, RegionRight.VIEW, null);
        if (region != null) all.add(region);
        for (PredicateFactory f : scope) {
            Predicate p = f.build(root, cq, cb);
            if (p != null) all.add(p);
        }
        for (FilterSpec spec : query.filters()) {
            ColumnDef def = schema.requireFilterable(spec.field(), spec.operator());
            all.add(FilterPredicates.build(spec, def, root, cq, cb));
        }
        return all;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void leftJoinFetch(Root<?> root, String association) {
        for (Fetch<?, ?> f : root.getFetches()) {
            if (f.getAttribute().getName().equals(association)) return;
        }
        ((Root) root).fetch(association, JoinType.LEFT);
    }

    private Predicate all(CriteriaBuilder cb, List<Predicate> parts) {
        return parts.isEmpty() ? cb.conjunction() : cb.and(parts.toArray(new Predicate[0]));
    }

    private <T> List<Order> orderBy(Root<T> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                    TableSchema schema, TableQuery query) {
        ColumnDef def = schema.requireSortable(query.sortField());
        Expression<?> path = def.path().resolve(root, cq, cb);
        List<Order> orders = new ArrayList<>();
        orders.add(query.sortAscending() ? cb.asc(path) : cb.desc(path));
        if (!"id".equals(def.name())) {
            orders.add(cb.desc(root.get("id")));
        }
        return orders;
    }
}
