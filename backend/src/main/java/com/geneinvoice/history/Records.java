package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The by-id loader of every read path that can be asked as of a date (§5.7.16): the live record, or, while a point
 * is bound, the record as it was then, rebuilt from its versions. A record that did not exist at the point is not
 * found, exactly as a missing one is.
 */
@Component
public class Records {

    @PersistenceContext
    private EntityManager em;

    private final VersionStore versions;
    private final UserRepository users;

    public Records(VersionStore versions, UserRepository users) {
        this.versions = versions;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public <T> Optional<T> find(Class<T> live, Long id) {
        if (id == null) return Optional.empty();
        if (!historical(live)) return Optional.ofNullable(em.find(live, id));
        return findAll(live, List.of(id)).stream().findFirst();
    }

    /** The records found among {@code ids}, in the order asked; a missing id is left out. */
    @Transactional(readOnly = true)
    public <T> List<T> findAll(Class<T> live, Collection<Long> ids) {
        List<Long> wanted = new ArrayList<>(new LinkedHashSet<>(ids));
        wanted.removeIf(Objects::isNull);
        if (wanted.isEmpty()) return List.of();
        Map<Long, T> found = historical(live) ? rehydrated(live, wanted) : loaded(live, wanted);
        return wanted.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private static boolean historical(Class<?> live) {
        return AsOf.active() && HistoryModel.isVersioned(live);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Map<Long, T> rehydrated(Class<T> live, List<Long> ids) {
        AsOfPoint point = AsOf.current();
        TypeHistory history = HistoryModel.of(live);
        List<VersionRow> valid = versions.validAt(history.versionType(), ids, point.instant());
        List<T> rebuilt = history.rehydrate(valid, new Rehydration(point, versions, em, users));
        Map<Long, T> byId = new HashMap<>();
        for (int i = 0; i < valid.size(); i++) {
            byId.put(valid.get(i).getId(), rebuilt.get(i));
        }
        return byId;
    }

    private <T> Map<Long, T> loaded(Class<T> live, List<Long> ids) {
        Map<Long, T> byId = new HashMap<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        for (int from = 0; from < ids.size(); from += VersionStore.CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + VersionStore.CHUNK));
            CriteriaQuery<T> q = cb.createQuery(live);
            Root<T> root = q.from(live);
            q.select(root).where(root.get("id").in(chunk));
            for (T record : em.createQuery(q).getResultList()) {
                byId.put((Long) em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(record), record);
            }
        }
        return byId;
    }
}
