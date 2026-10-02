package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What rebuilding records as they were at a point reads with: the point, the versions, the session and the users,
 * and batch loaders that remember what they read. A rebuilt record reaches the records it points at through these
 * loaders only, one query per kind for a whole page, never through a lazy load.
 */
public class Rehydration {

    private static final Logger log = LoggerFactory.getLogger(Rehydration.class);

    private final AsOfPoint point;
    private final VersionStore versions;
    private final EntityManager em;
    private final UserRepository users;

    private final Map<Class<?>, Map<Long, Object>> records = new HashMap<>();
    private final Map<Long, User> usersById = new HashMap<>();
    private final Map<Long, Region> regionsById = new HashMap<>();
    private final Map<Long, List<PaymentAllocationVersion>> allocationsByPayment = new HashMap<>();
    private final Map<String, Map<Long, List<Long>>> linksByKind = new HashMap<>();

    public Rehydration(AsOfPoint point, VersionStore versions, EntityManager em, UserRepository users) {
        this.point = point;
        this.versions = versions;
        this.em = em;
        this.users = users;
    }

    public AsOfPoint point() {
        return point;
    }

    public VersionStore versions() {
        return versions;
    }

    public EntityManager em() {
        return em;
    }

    public UserRepository users() {
        return users;
    }

    /**
     * The records of a versioned type as they were at the point, by id, in the order asked; one versions query for
     * the ids not read before. A record without a version at the point is one that history missed (it existed, as
     * something that did exist points at it). Only a product on an invoice line is then read live, with a WARN
     * (§5.7.15); for any other type the read is refused, because today's row would pass for history (§5.7.16).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <L> Map<Long, L> at(Class<L> live, Collection<Long> ids) {
        Map<Long, Object> known = records.computeIfAbsent(live, k -> new HashMap<>());
        List<Long> missing = new ArrayList<>(new LinkedHashSet<>(ids));
        missing.removeIf(id -> id == null || known.containsKey(id));
        if (!missing.isEmpty()) {
            TypeHistory history = HistoryModel.of(live);
            List<VersionRow> found = versions.validAt(history.versionType(), missing, point.instant());
            remember(known, found, history.rehydrate(found, this));
            List<Long> gaps = missing.stream().filter(id -> !known.containsKey(id)).toList();
            if (!gaps.isEmpty()) {
                log.warn("history.gap.read {} {} at {}", history.code(), gaps, point.instant());
                if (live != Product.class) throw AsOfException.unsupported();
                List<Object> lives = LiveReadGuard.allow(() -> gaps.stream()
                        .map(id -> (Object) em.find(live, id)).filter(Objects::nonNull).toList());
                List<VersionRow> snapshots = new ArrayList<>(history.snapshotAll(lives,
                        em.unwrap(SharedSessionContractImplementor.class)).values());
                remember(known, snapshots, history.rehydrate(snapshots, this));
            }
        }
        Map<Long, L> result = new LinkedHashMap<>();
        for (Long id : ids) {
            Object record = id == null ? null : known.get(id);
            if (record != null) result.put(id, (L) record);
        }
        return result;
    }

    /** One record as it was at the point; null for a null id. */
    public <L> L at(Class<L> live, Long id) {
        return id == null ? null : at(live, List.of(id)).get(id);
    }

    /**
     * The allocations each payment had at the point, oldest first; one versions query for the payments not read
     * before. A payment with none (voided since, or not yet applied) maps to an empty list.
     */
    public Map<Long, List<PaymentAllocationVersion>> allocationsAt(Collection<Long> paymentIds) {
        List<Long> missing = new ArrayList<>(new LinkedHashSet<>(paymentIds));
        missing.removeIf(id -> id == null || allocationsByPayment.containsKey(id));
        if (!missing.isEmpty()) {
            missing.forEach(id -> allocationsByPayment.put(id, new ArrayList<>()));
            for (PaymentAllocationVersion v : versions.validAtBy(PaymentAllocationVersion.class, "paymentId",
                    missing, point.instant())) {
                allocationsByPayment.get(v.getPaymentId()).add(v);
            }
        }
        Map<Long, List<PaymentAllocationVersion>> result = new LinkedHashMap<>();
        for (Long id : paymentIds) {
            if (id != null) result.put(id, List.copyOf(allocationsByPayment.get(id)));
        }
        return result;
    }

    /**
     * The targets each owner was linked to at the point by a {@link LinkHistory} of {@code kind}, in ascending id;
     * one query for the owners not read before.
     */
    public Map<Long, List<Long>> linksAt(String kind, Collection<Long> ownerIds) {
        Map<Long, List<Long>> known = linksByKind.computeIfAbsent(kind, k -> new HashMap<>());
        List<Long> missing = new ArrayList<>(new LinkedHashSet<>(ownerIds));
        missing.removeIf(id -> id == null || known.containsKey(id));
        if (!missing.isEmpty()) {
            Map<Long, TreeSet<Long>> targets = new HashMap<>();
            missing.forEach(id -> targets.put(id, new TreeSet<>()));
            for (EntityLinkVersion link : versions.linksAt(kind, missing, point.instant())) {
                targets.get(link.getOwnerId()).add(link.getId());
            }
            targets.forEach((owner, ids) -> known.put(owner, List.copyOf(ids)));
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Long id : ownerIds) {
            if (id != null) result.put(id, known.get(id));
        }
        return result;
    }

    /**
     * People by id, as they are now (users have no history). Someone since deleted is a stand-in named "Deleted
     * user", so a record that pointed at them still says someone did.
     */
    public Map<Long, User> usersOrStub(Collection<Long> ids) {
        List<Long> missing = new ArrayList<>(new LinkedHashSet<>(ids));
        missing.removeIf(id -> id == null || usersById.containsKey(id));
        if (!missing.isEmpty()) {
            users.findAllById(missing).forEach(u -> usersById.put(u.getId(), u));
            for (Long id : missing) {
                usersById.computeIfAbsent(id, gone -> User.builder()
                        .id(gone).fullName("Deleted user").active(false).build());
            }
        }
        Map<Long, User> result = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id != null) result.put(id, usersById.get(id));
        }
        return result;
    }

    public User userOrStub(Long id) {
        return id == null ? null : usersOrStub(List.of(id)).get(id);
    }

    /** Regions by id, as they are now: a record's region at the time is an id, its name is today's. */
    public Map<Long, Region> regions(Collection<Long> ids) {
        List<Long> missing = new ArrayList<>(new LinkedHashSet<>(ids));
        missing.removeIf(id -> id == null || regionsById.containsKey(id));
        if (!missing.isEmpty()) {
            em.createQuery("select r from Region r where r.id in :ids", Region.class)
                    .setParameter("ids", missing).getResultList()
                    .forEach(r -> regionsById.put(r.getId(), r));
            // A region deleted since: its id is all that is left of it.
            for (Long id : missing) {
                regionsById.computeIfAbsent(id, gone -> Region.builder().id(gone).build());
            }
        }
        Map<Long, Region> result = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id != null) result.put(id, regionsById.get(id));
        }
        return result;
    }

    public Region region(Long id) {
        return id == null ? null : regions(List.of(id)).get(id);
    }

    private static void remember(Map<Long, Object> known, List<? extends VersionRow> versions, List<?> rebuilt) {
        for (int i = 0; i < versions.size(); i++) {
            known.put(versions.get(i).getId(), rebuilt.get(i));
        }
    }
}
