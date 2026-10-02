package com.geneinvoice.history;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.history.HistoryWork.Change;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.HistoryTransactionRow;
import com.geneinvoice.region.Region;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Turns one transaction's touched records into versions (§5.7.4). Runs just before COMMIT, after Hibernate's
 * last flush and on the same connection, so every row the transaction wrote is already locked: a concurrent
 * writer of the same record waits for this commit and then reads the version written here.
 *
 * <p>Per record: the open version is closed and a new one opened at the transaction's instant when the
 * snapshot differs; nothing is written when it does not. Links are versioned row by row.
 */
@Component
public class HistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoryWriter.class);
    private static final String REGION = "region";

    private final Clock clock;
    private final CurrentUser currentUser;
    private final VersionStore versions;
    private final HistoryFaults faults;
    private final boolean strict;

    public HistoryWriter(Clock clock, CurrentUser currentUser, VersionStore versions, HistoryFaults faults,
                         @Value("${app.history.strict:false}") boolean strict) {
        this.clock = clock;
        this.currentUser = currentUser;
        this.versions = versions;
        this.faults = faults;
        this.strict = strict;
    }

    /** Who wrote: stamped on every version of the transaction. */
    private record Stamp(String txId, String origin, Long originRefId, Long actorUserId) {
        void on(VersionRow v) {
            v.setTxId(txId);
            v.setOrigin(origin);
            v.setOriginRefId(originRefId);
            v.setActorUserId(actorUserId);
        }
    }

    public void write(SessionImplementor s, HistoryWork work) {
        if (AsOf.active()) throw AsOfException.readOnly(AsOf.current().date());
        if (work.isEmpty() && !work.needsInstant()) return;
        work.startWriting();
        // Bound here, not earlier: every row lock the transaction takes is held by now.
        Instant t = work.instant(clock);
        WriteOrigin origin = WriteOrigins.current(currentUser);
        Stamp stamp = new Stamp(work.txId(), origin.kind().name(), originRef(origin), WriteOrigins.actor(currentUser));
        Instant reached = t;
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            SortedMap<Long, Set<Change>> keys = work.touched(history.code());
            if (!keys.isEmpty()) reached = later(reached, writeType(s, history, keys, t, stamp, work));
        }
        for (LinkHistory link : HistoryModel.links()) {
            SortedMap<Long, Set<Change>> owners = work.touched(link.code());
            if (!owners.isEmpty()) reached = later(reached, writeLinks(s, link, owners.keySet(), t, stamp, work));
        }
        // When the transaction's outbox events happened: the rules are evaluated as of this instant (D10). It is the
        // latest start or end given above, not t: a version pushed past t (a skewed or equal clock) must be in view.
        if (work.needsInstant()) s.persist(new HistoryTransactionRow(work.txId(), reached));
        s.flush();
    }

    private static Long originRef(WriteOrigin origin) {
        return switch (origin.kind()) {
            case AUTOMATION -> origin.ruleId();
            case APPROVAL -> origin.pendingChangeId();
            default -> null;
        };
    }

    /** Versions one type; returns the latest instant it gave a version (t when it gave none). */
    private <L, V extends VersionRow> Instant writeType(SessionImplementor s, TypeHistory<L, V> history,
                                                        SortedMap<Long, Set<Change>> keys, Instant t, Stamp stamp,
                                                        HistoryWork work) {
        Instant reached = t;
        List<Long> ids = new ArrayList<>(keys.keySet());
        for (int from = 0; from < ids.size(); from += VersionStore.CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + VersionStore.CHUNK));
            Map<Long, V> opens = latest(versions.open(s, history.versionType(), chunk));
            Map<Long, L> lives = new LinkedHashMap<>();
            for (Long id : chunk) {
                if (keys.get(id).contains(Change.DELETED)) continue;
                L live = s.get(history.liveType(), id);
                if (live != null) lives.put(id, live);
            }
            rereadMoved(s, history.liveType(), lives);
            Set<Long> failed = new HashSet<>();
            Map<Long, V> nexts = snapshot(s, history, lives, failed);
            for (Long id : chunk) {
                if (failed.contains(id)) continue;   // left stale for the reconciler
                Set<Change> changes = keys.get(id);
                V open = opens.get(id);
                V next = nexts.get(id);
                if (next == null) {
                    if (open != null) {
                        open.setValidTo(after(t, open));
                        reached = later(reached, open.getValidTo());
                        work.written(history.code(), id);
                    }
                    continue;
                }
                if (open != null && history.sameState(open, next)) continue;
                if (open != null && changes.contains(Change.CHILD) && !changes.contains(Change.UPDATED)) {
                    // Only a child row was written, so the root is not locked yet. Take it, then snapshot again
                    // and compare with the open version: a concurrent writer of another child may have committed
                    // meanwhile, and the snapshot taken before the lock does not have its change.
                    L live = lives.get(id);
                    s.refresh(live, LockModeType.PESSIMISTIC_WRITE);
                    next = snapshot(s, history, Map.of(id, live), failed).get(id);
                    if (next == null) continue;   // failed: left stale for the reconciler
                    open = latest(versions.open(s, history.versionType(), List.of(id))).get(id);
                    if (open != null && history.sameState(open, next)) continue;
                }
                Instant validFrom = open == null ? t : after(t, open);
                if (open != null) {
                    open.setValidTo(validFrom);
                } else if (!changes.contains(Change.INSERTED) && !work.isPinned()) {
                    log.warn("history.gap {} {}: no current version; history of it starts now", history.code(), id);
                }
                next.setValidFrom(validFrom);
                next.setValidTo(VersionRow.OPEN);
                stamp.on(next);
                s.persist(next);
                reached = later(reached, validFrom);
                work.written(history.code(), id);
            }
        }
        return reached;
    }

    /**
     * Re-reads the copies whose row has another region. {@code region_id} is never written by an ORM update (F3),
     * so a copy loaded before a move committed keeps the old region while its row has the new one, and a version
     * taken from it would put the old region back. The flush has run, so re-reading loses nothing.
     */
    private <L> void rereadMoved(SessionImplementor s, Class<L> type, Map<Long, L> lives) {
        if (lives.isEmpty()) return;
        EntityType<L> entity = s.getFactory().getJpaMetamodel().entity(type);
        if (entity.getAttributes().stream().noneMatch(a -> a.getName().equals(REGION))) return;
        Map<Long, Long> regions = new HashMap<>();
        s.createQuery("select e.id, r.id from " + entity.getName() + " e left join e.region r where e.id in :ids",
                        Object[].class)
                .setParameter("ids", lives.keySet())
                .getResultList()
                .forEach(row -> regions.put((Long) row[0], (Long) row[1]));
        EntityPersister persister = s.getFactory().getMappingMetamodel().getEntityDescriptor(type);
        lives.forEach((id, live) -> {
            Region held = (Region) persister.getPropertyValue(live, REGION);
            if (!Objects.equals(Region.idOf(held), regions.get(id))) s.refresh(live);
        });
    }

    /**
     * Snapshots of the live records. A Java-side mapping error is caught per record: in production the record is
     * skipped (its open version stays as it was, for the reconciler); in strict mode (tests) it is rethrown.
     * Database errors are never caught.
     */
    private <L, V extends VersionRow> Map<Long, V> snapshot(SessionImplementor s, TypeHistory<L, V> history,
                                                            Map<Long, L> lives, Set<Long> failed) {
        try {
            lives.keySet().forEach(id -> faults.beforeSnapshot(history.code(), id));
            return history.snapshotAll(lives.values(), s);
        } catch (PersistenceException | DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            if (strict) throw e;
        }
        Map<Long, V> nexts = new LinkedHashMap<>();
        lives.forEach((id, live) -> {
            try {
                faults.beforeSnapshot(history.code(), id);
                nexts.putAll(history.snapshotAll(List.of(live), s));
            } catch (PersistenceException | DataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                failed.add(id);
                log.error("history.capture.failed {} {}", history.code(), id, e);
            }
        });
        return nexts;
    }

    /**
     * Versions one link kind; returns the latest instant it gave a row (t when it gave none). The owners are locked
     * first: a relink writes only join rows, so without it a concurrent relink of the same owner could commit between
     * reading the targets and reading the open rows, and its new open row, with no target seen, would be closed.
     */
    private Instant writeLinks(SessionImplementor s, LinkHistory link, Collection<Long> ownerIds, Instant t,
                               Stamp stamp, HistoryWork work) {
        Instant reached = t;
        List<Long> owners = new ArrayList<>(ownerIds);
        lockOwners(s, link, owners);
        // Owners deleted in this transaction have no targets left: every open row of theirs closes.
        Map<Long, Set<Long>> current = link.currentTargets(s, owners);
        owners.forEach(owner -> faults.betweenLinkReads(link.code(), owner));
        Map<Long, List<EntityLinkVersion>> openRows = new TreeMap<>();
        for (EntityLinkVersion row : versions.openLinks(s, link.kind(), owners)) {
            openRows.computeIfAbsent(row.getOwnerId(), o -> new ArrayList<>()).add(row);
        }
        for (Long owner : owners) {
            List<EntityLinkVersion> rows = openRows.getOrDefault(owner, List.of());
            Instant at = rows.stream().map(r -> after(t, r)).max(Instant::compareTo).orElse(t);
            Set<Long> targets = current.getOrDefault(owner, Set.of());
            Set<Long> stillOpen = new HashSet<>();
            for (EntityLinkVersion row : rows) {
                if (targets.contains(row.getId())) {
                    stillOpen.add(row.getId());
                } else {
                    row.setValidTo(at);
                    reached = later(reached, at);
                    work.written(link.code(), owner);
                }
            }
            for (Long target : targets) {
                if (stillOpen.contains(target)) continue;
                reached = later(reached, at);
                work.written(link.code(), owner);
                EntityLinkVersion row = new EntityLinkVersion();
                row.setKind(link.kind());
                row.setOwnerId(owner);
                row.setId(target);
                row.setValidFrom(at);
                row.setValidTo(VersionRow.OPEN);
                stamp.on(row);
                s.persist(row);
            }
        }
        return reached;
    }

    /**
     * Locks the owners' rows, in ascending id as writeType's locks are taken. Callers that relink already hold the
     * customer and its invoices, so the order stays customer, invoices, promise (§5.11.5). An owner deleted in this
     * transaction has no row to lock.
     */
    private static void lockOwners(SessionImplementor s, LinkHistory link, List<Long> owners) {
        String ownerEntity = s.getFactory().getJpaMetamodel().entity(link.owner()).getName();
        List<Long> ids = owners.stream().sorted().toList();
        for (int from = 0; from < ids.size(); from += VersionStore.CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + VersionStore.CHUNK));
            s.createQuery("select o.id from " + ownerEntity + " o where o.id in :ids order by o.id", Long.class)
                    .setParameter("ids", chunk)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
        }
    }

    /** When a version that started at {@code open.validFrom} can end: now, but never before it began. */
    private static Instant after(Instant t, VersionRow open) {
        Instant earliest = open.getValidFrom().plus(1, ChronoUnit.MICROS);
        return t.isBefore(earliest) ? earliest : t;
    }

    private static Instant later(Instant a, Instant b) {
        return b.isAfter(a) ? b : a;
    }

    /** The newest open version per record; a duplicate (never written here) is the reconciler's to close. */
    private static <V extends VersionRow> Map<Long, V> latest(List<V> opens) {
        Map<Long, V> latest = new LinkedHashMap<>();
        for (V v : opens) latest.put(v.getId(), v);   // ordered by id, then validFrom: the last wins
        return latest;
    }
}
