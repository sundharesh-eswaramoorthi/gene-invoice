package com.geneinvoice.history;

import com.geneinvoice.promise.PaymentPromise;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A many-to-many link versioned row by row, as {@code (kind, owner, target)} in {@code entity_link_versions}.
 * Relinking writes only join rows, so the writer locks the owner's own row before it versions the links: two
 * relinks of one owner then take turns instead of each closing the row the other opened.
 */
public record LinkHistory(String kind, Class<?> owner, String collectionAttribute) {

    public static final String PROMISE_INVOICE = "PROMISE_INVOICE";
    public static final String PROMISE_PAYMENT = "PROMISE_PAYMENT";

    public static final LinkHistory PROMISE_INVOICES =
            new LinkHistory(PROMISE_INVOICE, PaymentPromise.class, "invoices");
    public static final LinkHistory PROMISE_PAYMENTS =
            new LinkHistory(PROMISE_PAYMENT, PaymentPromise.class, "payments");

    public static final String CODE_PREFIX = "LINK:";
    public static final int RANK = 45;

    public String code() {
        return CODE_PREFIX + kind;
    }

    public int rank() {
        return RANK;
    }

    /** Each owner's current targets; an owner with none (or no longer there) maps to an empty set. */
    public Map<Long, Set<Long>> currentTargets(SharedSessionContractImplementor s, Collection<Long> ownerIds) {
        Map<Long, Set<Long>> targets = new TreeMap<>();
        for (Long ownerId : ownerIds) targets.put(ownerId, new TreeSet<>());
        String entity = s.getFactory().getJpaMetamodel().entity(owner).getName();
        String query = "select o.id, t.id from " + entity + " o join o." + collectionAttribute + " t"
                + " where o.id in :ids";
        List<Long> ids = new ArrayList<>(targets.keySet());
        for (int from = 0; from < ids.size(); from += VersionStore.CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + VersionStore.CHUNK));
            for (Object[] row : s.createQuery(query, Object[].class).setParameter("ids", chunk).getResultList()) {
                targets.get((Long) row[0]).add((Long) row[1]);
            }
        }
        return targets;
    }
}
