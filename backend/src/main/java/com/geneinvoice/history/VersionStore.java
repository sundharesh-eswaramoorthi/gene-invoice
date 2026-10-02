package com.geneinvoice.history;

import com.geneinvoice.history.versions.EntityLinkVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Reads versions: the current ones, and the ones valid at a point ({@code validFrom <= p < validTo}). Every
 * value is bound as a parameter and every {@code in} list is cut into chunks of {@value #CHUNK}. Results come in
 * record id order, then oldest first.
 */
@Component
public class VersionStore {

    public static final int CHUNK = 500;

    @PersistenceContext
    private EntityManager em;

    public <V extends VersionRow> List<V> open(Class<V> type, Collection<Long> ids) {
        return open(em, type, ids);
    }

    /** The current versions, read on {@code session}: the writer's own, while its transaction commits. */
    public <V extends VersionRow> List<V> open(EntityManager session, Class<V> type, Collection<Long> ids) {
        return byChunk(session, type, "id", ids, (v, cb) -> cb.equal(v.get("validTo"), VersionRow.OPEN));
    }

    public <V extends VersionRow> List<V> validAt(Class<V> type, Collection<Long> ids, Instant p) {
        return byChunk(em, type, "id", ids, (v, cb) -> validAt(v, cb, p));
    }

    /** Versions valid at {@code p} whose {@code attribute} (a column, such as customerId) is in {@code values}. */
    public <V extends VersionRow> List<V> validAtBy(Class<V> type, String attribute, Collection<Long> values,
                                                    Instant p) {
        Attribute<?, ?> declared = em.getMetamodel().entity(type).getAttribute(attribute);
        if (declared.isAssociation() || declared.isCollection()) {
            throw new IllegalArgumentException(type.getSimpleName() + "." + attribute + " is not a column");
        }
        return byChunk(em, type, attribute, values, (v, cb) -> validAt(v, cb, p));
    }

    public List<EntityLinkVersion> openLinks(String kind, Collection<Long> owners) {
        return openLinks(em, kind, owners);
    }

    public List<EntityLinkVersion> openLinks(EntityManager session, String kind, Collection<Long> owners) {
        return byChunk(session, EntityLinkVersion.class, "ownerId", owners,
                (v, cb) -> cb.and(cb.equal(v.get("kind"), kind), cb.equal(v.get("validTo"), VersionRow.OPEN)));
    }

    public List<EntityLinkVersion> linksAt(String kind, Collection<Long> owners, Instant p) {
        return byChunk(em, EntityLinkVersion.class, "ownerId", owners,
                (v, cb) -> cb.and(cb.equal(v.get("kind"), kind), validAt(v, cb, p)));
    }

    private static Predicate validAt(Root<?> v, CriteriaBuilder cb, Instant p) {
        return cb.and(cb.lessThanOrEqualTo(v.<Instant>get("validFrom"), p),
                cb.greaterThan(v.<Instant>get("validTo"), p));
    }

    private static <V> List<V> byChunk(EntityManager em, Class<V> type, String attribute, Collection<Long> values,
                                       BiFunction<Root<V>, CriteriaBuilder, Predicate> window) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(values));
        distinct.removeIf(Objects::isNull);
        List<V> rows = new ArrayList<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        for (int from = 0; from < distinct.size(); from += CHUNK) {
            List<Long> chunk = distinct.subList(from, Math.min(distinct.size(), from + CHUNK));
            CriteriaQuery<V> q = cb.createQuery(type);
            Root<V> v = q.from(type);
            List<Order> order = new ArrayList<>();
            if (!"id".equals(attribute)) order.add(cb.asc(v.get(attribute)));
            order.add(cb.asc(v.get("id")));
            order.add(cb.asc(v.get("validFrom")));
            order.add(cb.asc(v.get("versionId")));
            q.select(v).where(v.get(attribute).in(chunk), window.apply(v, cb)).orderBy(order);
            rows.addAll(em.createQuery(q).getResultList());
        }
        return rows;
    }
}
