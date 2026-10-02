package com.geneinvoice.history;

import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.history.versions.EntityLinkVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the live tables with their open versions. {@link #check()} only reports: records with no open
 * version, open versions that say something else than the record, open versions of records that are gone, more
 * than one open version of a record, and links whose open rows differ from the live association.
 * {@link #reconcile()} repairs what a check finds, each chunk in a transaction of its own that locks the rows
 * before it re-reads them: a record a concurrent save brought back in step meanwhile is left alone.
 */
@Component
public class HistoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(HistoryReconciler.class);

    @PersistenceContext
    private EntityManager em;

    private final VersionStore versions;
    private final HistoryTracker tracker;
    private final HistoryTransactions transactions;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate repair;

    public HistoryReconciler(VersionStore versions, HistoryTracker tracker, HistoryTransactions transactions,
                             PlatformTransactionManager transactionManager) {
        this.versions = versions;
        this.tracker = tracker;
        this.transactions = transactions;
        // A transaction of its own: the chunks clear the session, which must never be a caller's.
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
        // One per repaired chunk, so a failure costs that chunk only and no lock is held longer than it takes.
        this.repair = new TransactionTemplate(transactionManager);
        this.repair.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static final class Findings {
        int checked;
        final List<String> missing = new ArrayList<>();
        final List<String> drifted = new ArrayList<>();
        final List<String> orphaned = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final List<String> linkDrift = new ArrayList<>();
        /** Per type code: the records whose open version is missing, stale or left behind. */
        final Map<String, SortedSet<Long>> stale = new LinkedHashMap<>();
        /** Per type code: the records with more than one open version. */
        final Map<String, SortedSet<Long>> doubled = new LinkedHashMap<>();
        /** Per link code: the owners whose open link rows differ from the live association. */
        final Map<String, SortedSet<Long>> linkOwners = new LinkedHashMap<>();

        void add(Map<String, SortedSet<Long>> ids, String code, Long id) {
            ids.computeIfAbsent(code, c -> new TreeSet<>()).add(id);
        }

        SortedSet<Long> of(Map<String, SortedSet<Long>> ids, String code) {
            return ids.getOrDefault(code, new TreeSet<>());
        }

        ReconcileReport report(int repaired) {
            return new ReconcileReport(checked, missing, drifted, orphaned, duplicates, linkDrift, repaired);
        }
    }

    public ReconcileReport check() {
        return find().report(0);
    }

    /**
     * Brings history back in step with the live tables and says what it found and how many records it repaired.
     * Stale, missing and left-behind records get their version through {@link HistoryTracker#touch} with a lock,
     * under {@link WriteOrigin#reconcile()}; of a record with several open versions all but the newest are closed.
     */
    public ReconcileReport reconcile() {
        Findings found = find();
        Set<String> repaired = new HashSet<>();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            SortedSet<Long> doubled = found.of(found.doubled, history.code());
            SortedSet<Long> ids = new TreeSet<>(found.of(found.stale, history.code()));
            ids.addAll(doubled);
            for (List<Long> chunk : chunks(ids)) {
                repaired.addAll(repairChunk(history.liveType(), chunk, () -> closeDoubled(history, chunk, doubled)));
            }
        }
        Map<Class<?>, SortedSet<Long>> owners = new LinkedHashMap<>();
        for (LinkHistory link : HistoryModel.links()) {
            owners.computeIfAbsent(link.owner(), o -> new TreeSet<>()).addAll(found.of(found.linkOwners, link.code()));
        }
        owners.forEach((owner, ids) -> {
            for (List<Long> chunk : chunks(ids)) {
                repaired.addAll(repairChunk(owner, chunk, () -> closeDoubledLinks(owner, chunk)));
            }
        });
        ReconcileReport report = found.report(repaired.size());
        if (report.problems().isEmpty()) {
            log.info("history.reconcile checked={}: in step", report.checked());
        } else {
            log.warn("history.reconcile checked={} missing={} drifted={} orphaned={} duplicates={} linkDrift={} "
                            + "repaired={}", report.checked(), report.missing().size(), report.drifted().size(),
                    report.orphaned().size(), report.duplicates().size(), report.linkDrift().size(), repaired.size());
        }
        return report;
    }

    /**
     * One chunk in a transaction of its own: {@code before} first (closing extra open rows), then the records are
     * locked and re-read, and the writer versions each that still differs at commit. Returns what it wrote, as
     * {@code "CODE id"}.
     */
    private Set<String> repairChunk(Class<?> live, List<Long> ids, Runnable before) {
        try {
            HistoryWork work = WriteOrigins.callWith(WriteOrigin.reconcile(), () -> repair.execute(status -> {
                before.run();
                tracker.touch(live, ids, true);
                return transactions.current(em);
            }));
            return work.written();
        } catch (RuntimeException e) {
            // The chunk rolled back and stays as it was, for the next run; the other chunks still get repaired.
            log.error("history.reconcile.failed {} {}..{}", live.getSimpleName(), ids.get(0),
                    ids.get(ids.size() - 1), e);
            return Set.of();
        }
    }

    /** Of each record with several open versions, all but the one that began last end where it begins. */
    private <L, V extends VersionRow> void closeDoubled(TypeHistory<L, V> history, List<Long> chunk,
                                                       Set<Long> doubled) {
        List<Long> ids = chunk.stream().filter(doubled::contains).toList();
        if (ids.isEmpty()) return;
        Map<Long, List<V>> opens = new TreeMap<>();
        for (V open : versions.open(em, history.versionType(), ids)) {
            opens.computeIfAbsent(open.getId(), i -> new ArrayList<>()).add(open);
        }
        HistoryWork work = transactions.current(em);
        opens.forEach((id, rows) -> {
            if (closeAllButNewest(rows)) work.written(history.code(), id);
        });
        em.flush();
    }

    /** Of each link row open more than once, all but the newest end where it begins. */
    private void closeDoubledLinks(Class<?> owner, List<Long> owners) {
        HistoryWork work = transactions.current(em);
        for (LinkHistory link : HistoryModel.linksOf(owner)) {
            Map<List<Long>, List<EntityLinkVersion>> rows = new LinkedHashMap<>();
            for (EntityLinkVersion row : versions.openLinks(em, link.kind(), owners)) {
                rows.computeIfAbsent(List.of(row.getOwnerId(), row.getId()), k -> new ArrayList<>()).add(row);
            }
            rows.forEach((key, same) -> {
                if (closeAllButNewest(same)) work.written(link.code(), key.get(0));
            });
        }
        em.flush();
    }

    /** Rows come oldest first; returns whether any was closed. */
    private static boolean closeAllButNewest(List<? extends VersionRow> rows) {
        if (rows.size() < 2) return false;
        VersionRow newest = rows.get(rows.size() - 1);
        for (VersionRow row : rows.subList(0, rows.size() - 1)) {
            Instant earliest = row.getValidFrom().plus(1, ChronoUnit.MICROS);
            row.setValidTo(newest.getValidFrom().isBefore(earliest) ? earliest : newest.getValidFrom());
        }
        return true;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> sorted = new ArrayList<>(ids);
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < sorted.size(); from += VersionStore.CHUNK) {
            chunks.add(sorted.subList(from, Math.min(sorted.size(), from + VersionStore.CHUNK)));
        }
        return chunks;
    }

    private Findings find() {
        return readOnly.execute(status -> {
            Findings findings = new Findings();
            for (TypeHistory<?, ?> history : HistoryModel.types()) checkType(history, findings);
            for (LinkHistory link : HistoryModel.links()) checkLinks(link, findings);
            return findings;
        });
    }

    private <L, V extends VersionRow> void checkType(TypeHistory<L, V> history, Findings findings) {
        String live = entityName(history.liveType());
        String version = entityName(history.versionType());
        SessionImplementor session = em.unwrap(SessionImplementor.class);
        Long last = 0L;
        while (true) {
            List<L> chunk = em.createQuery("select e from " + live + " e where e.id > :last order by e.id",
                            history.liveType())
                    .setParameter("last", last)
                    .setMaxResults(VersionStore.CHUNK)
                    .getResultList();
            if (chunk.isEmpty()) break;
            Map<Long, V> nexts = history.snapshotAll(chunk, session);
            Map<Long, V> opens = new TreeMap<>();
            for (V open : versions.open(em, history.versionType(), nexts.keySet())) opens.put(open.getId(), open);
            for (Map.Entry<Long, V> next : nexts.entrySet()) {
                findings.checked++;
                V open = opens.get(next.getKey());
                String key = history.code() + " " + next.getKey();
                if (open == null) {
                    findings.missing.add(key);
                    findings.add(findings.stale, history.code(), next.getKey());
                } else if (!history.sameState(open, next.getValue())) {
                    findings.drifted.add(key);
                    findings.add(findings.stale, history.code(), next.getKey());
                }
            }
            last = new ArrayList<>(nexts.keySet()).get(nexts.size() - 1);
            em.clear();
        }
        em.createQuery("select distinct v.id from " + version + " v where v.validTo = :open"
                        + " and not exists (select 1 from " + live + " e where e.id = v.id) order by v.id", Long.class)
                .setParameter("open", VersionRow.OPEN)
                .getResultList()
                .forEach(id -> {
                    findings.orphaned.add(history.code() + " " + id);
                    findings.add(findings.stale, history.code(), id);
                });
        em.createQuery("select v.id from " + version + " v where v.validTo = :open"
                        + " group by v.id having count(v) > 1 order by v.id", Long.class)
                .setParameter("open", VersionRow.OPEN)
                .getResultList()
                .forEach(id -> {
                    findings.duplicates.add(history.code() + " " + id);
                    findings.add(findings.doubled, history.code(), id);
                });
    }

    private void checkLinks(LinkHistory link, Findings findings) {
        String owner = entityName(link.owner());
        SessionImplementor session = em.unwrap(SessionImplementor.class);
        Long last = 0L;
        while (true) {
            List<Long> owners = em.createQuery("select o.id from " + owner + " o where o.id > :last order by o.id",
                            Long.class)
                    .setParameter("last", last)
                    .setMaxResults(VersionStore.CHUNK)
                    .getResultList();
            if (owners.isEmpty()) break;
            Map<Long, Set<Long>> current = link.currentTargets(session, owners);
            Map<Long, List<Long>> open = new TreeMap<>();
            for (EntityLinkVersion row : versions.openLinks(em, link.kind(), owners)) {
                open.computeIfAbsent(row.getOwnerId(), o -> new ArrayList<>()).add(row.getId());
            }
            for (Long id : owners) {
                List<Long> rows = open.getOrDefault(id, List.of());
                Set<Long> targets = current.getOrDefault(id, Set.of());
                if (rows.size() != new TreeSet<>(rows).size() || !targets.equals(new TreeSet<>(rows))) {
                    findings.linkDrift.add(link.code() + " " + id + ": " + targets + " live, " + rows + " open");
                    findings.add(findings.linkOwners, link.code(), id);
                }
            }
            last = owners.get(owners.size() - 1);
            em.clear();
        }
        em.createQuery("select distinct l.ownerId from EntityLinkVersion l where l.kind = :kind"
                        + " and l.validTo = :open and not exists (select 1 from " + owner + " o where o.id = l.ownerId)"
                        + " order by l.ownerId", Long.class)
                .setParameter("kind", link.kind())
                .setParameter("open", VersionRow.OPEN)
                .getResultList()
                .forEach(id -> {
                    findings.linkDrift.add(link.code() + " " + id + ": owner gone, links still open");
                    findings.add(findings.linkOwners, link.code(), id);
                });
    }

    private String entityName(Class<?> type) {
        return em.getMetamodel().entity(type).getName();
    }
}
