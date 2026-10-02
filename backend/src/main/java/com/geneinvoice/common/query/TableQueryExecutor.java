package com.geneinvoice.common.query;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.history.HistoryModel;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.history.VersionStore;
import com.geneinvoice.user.UserRepository;
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
 * Runs list queries described by a {@link TableSchema}. While a point is bound (as-of reads), a versioned record is
 * read from its versions valid at the point, with the same filters, order and paging, and rebuilt as it was then
 * (§5.7.13); everything else is untouched, so the live SQL is exactly what it always was.
 */
@Component
public class TableQueryExecutor {

    public static final int BULK_ID_LIMIT = 5000;

    @PersistenceContext
    private EntityManager em;

    private final VersionStore versionStore;
    private final UserRepository userRepository;

    public TableQueryExecutor(VersionStore versionStore, UserRepository userRepository) {
        this.versionStore = versionStore;
        this.userRepository = userRepository;
    }

    public record Page<T>(List<T> content, long total) {}

    @Transactional(readOnly = true)
    public <T> Page<T> run(Class<T> type, TableSchema schema, TableQuery query,
                           List<PredicateFactory> scope, List<String> fetchAssociations) {
        if (AsOf.active() && HistoryModel.isVersioned(type)) {
            return runHistorical(type, schema, query, scope);
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<T> cq = cb.createQuery(type);
        Root<T> root = cq.from(type);
        for (String assoc : fetchAssociations) {
            leftJoinFetch(root, assoc);
        }
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));

        List<T> content = page(em.createQuery(cq), query);
        return new Page<>(content, count(type, schema, query, scope));
    }

    /**
     * The page as it was at the bound point: the versions valid then, filtered, ordered and paged as the live rows
     * would be (no fetch joins: a version's associations are ids), then rebuilt with one query per related kind.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Page<T> runHistorical(Class<T> type, TableSchema schema, TableQuery query,
                                      List<PredicateFactory> scope) {
        TypeHistory history = HistoryModel.of(type);
        Class<? extends VersionRow> versionType = history.versionType();
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery cq = cb.createQuery(versionType);
        Root<?> root = cq.from(versionType);
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));

        List<VersionRow> versions = page(em.createQuery(cq), query);
        List<T> content = history.rehydrate(versions,
                new Rehydration(AsOf.current(), versionStore, em, userRepository));
        return new Page<>(content, count(type, schema, query, scope));
    }

    /** The rows of the query's page. */
    private <R> List<R> page(TypedQuery<R> tq, TableQuery query) {
        if (query.size() != Integer.MAX_VALUE) {
            // A page number far past the end overflows int arithmetic; there is nothing on it
            // anyway, so it is an empty page rather than a 500 (D-40).
            long offset = (long) query.page() * query.size();
            if (offset > Integer.MAX_VALUE) return List.of();
            tq.setFirstResult((int) offset);
            tq.setMaxResults(query.size());
        } else {
            tq.setMaxResults(BULK_ID_LIMIT);
        }
        return tq.getResultList();
    }

    @Transactional(readOnly = true)
    public <T> long count(Class<T> type, TableSchema schema, TableQuery query, List<PredicateFactory> scope) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<?> countRoot = Temporal.from(countQuery, type);
        countQuery.select(cb.count(countRoot));
        countQuery.where(all(cb, predicates(countRoot, countQuery, cb, schema, query, scope)));
        return em.createQuery(countQuery).getSingleResult();
    }

    @Transactional(readOnly = true)
    public <T> boolean inScope(Class<T> type, TableSchema schema, Long id, List<PredicateFactory> scope) {
        if (scope.isEmpty()) return true;
        return count(type, schema, TableQuery.parseUnpaged(schema, null, List.of("id:eq:" + id)), scope) > 0;
    }

    @Transactional(readOnly = true)
    public <T> List<Long> ids(Class<T> type, TableSchema schema, TableQuery query,
                              List<PredicateFactory> scope, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<?> root = Temporal.from(cq, type);
        cq.select(root.get("id"));
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));
        return em.createQuery(cq).setMaxResults(limit).getResultList();
    }

    /** The ids on the query's page, at its offset: any page of a set, however far in. */
    @Transactional(readOnly = true)
    public <T> List<Long> idsPage(Class<T> type, TableSchema schema, TableQuery query,
                                  List<PredicateFactory> scope) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<?> root = Temporal.from(cq, type);
        cq.select(root.get("id"));
        cq.where(all(cb, predicates(root, cq, cb, schema, query, scope)));
        cq.orderBy(orderBy(root, cq, cb, schema, query));
        return page(em.createQuery(cq), query);
    }

    @Transactional(readOnly = true)
    public <T> Object[] aggregate(Class<T> type, TableSchema schema, TableQuery query,
                                  List<PredicateFactory> scope, AggregateSelections selections) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> root = Temporal.from(cq, type);
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

    private List<Predicate> predicates(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                       TableSchema schema, TableQuery query,
                                       List<PredicateFactory> scope) {
        List<Predicate> all = new ArrayList<>();
        Predicate valid = Temporal.validOrNull(root, cb);
        if (valid != null) all.add(valid);
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

    private List<Order> orderBy(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
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
