package com.geneinvoice.common.query;

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

/**
 * Runs a validated {@link TableQuery} against an entity: one content query and one count query,
 * both constrained by the caller's mandatory scope predicates AND the user's filter chips.
 */
@Component
public class TableQueryExecutor {

    /** Ceiling on ids materialised for "select all matching this filter" and CSV export. */
    public static final int BULK_ID_LIMIT = 5000;

    @PersistenceContext
    private EntityManager em;

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
            tq.setFirstResult(query.page() * query.size());
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

    /**
     * True when the row with this id passes the caller's scope. A read or write by id checks it, so
     * a record outside the caller's book is as unreachable by id as it is in the list (AC-A6).
     */
    @Transactional(readOnly = true)
    public <T> boolean inScope(Class<T> type, TableSchema schema, Long id, List<PredicateFactory> scope) {
        if (scope.isEmpty()) return true;
        return count(type, schema, TableQuery.parseUnpaged(schema, null, List.of("id:eq:" + id)), scope) > 0;
    }

    /** Ids of every row matching the filter, for "select all N" and export. */
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

    /**
     * Runs an aggregate over the full filtered set. The caller supplies the selections; the same
     * scope and filter predicates are applied, so tiles and table can never disagree (AC-E1/E2).
     */
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

    /** Left join that also fetches, so rendering a page does not fire N+1 selects. */
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

    /** Sort column plus a stable id tiebreak, so no row is skipped or repeated across pages (AC-D2). */
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
