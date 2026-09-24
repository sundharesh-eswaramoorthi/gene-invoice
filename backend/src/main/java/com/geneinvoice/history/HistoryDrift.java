package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOfContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Whether an as-of answer over one mirror is allowed to call itself exact (B3).
 *
 * <p>A row the reconciler REPAIRED is a row nobody watched change: its predecessor was closed at
 * the moment the sweep noticed, not at the moment the change happened, so every question asked
 * between those two instants has an answer that is off by the size of that window. That is a
 * detection, not a guarantee, and the contract's answer is to say so rather than to hide it —
 * {@code exact: false} with the reason in {@code notes}, on every response over an entity holding
 * a drifted row at or before T.
 *
 * <p>ONE indexed {@code exists} per response and not per row: the question is about the TABLE, and
 * the answer is the same for every record in the page (B3).
 */
@Service
@RequiredArgsConstructor
public class HistoryDrift {

    private final HistoryRegistry registry;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Does this mirror hold any repaired row whose version was in force at or before {@code t}?
     * A repair made AFTER the date being asked about says nothing about that date, which is why
     * the bound is on {@code validFrom} and not on the whole table (B3).
     */
    @Transactional(readOnly = true)
    public boolean anyDriftedAtOrBefore(Class<? extends HistoryRow> mirror, Instant t) {
        if (registry.forMirror(mirror) == null) {
            throw new IllegalArgumentException(mirror.getName() + " is not a mirror (B3)");
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<? extends HistoryRow> root = cq.from(mirror);
        cq.select(root.get("historyId")).where(cb.and(
                cb.isTrue(root.get("drifted")),
                cb.lessThanOrEqualTo(root.<Instant>get("validFrom"), t)));
        return !entityManager.createQuery(cq).setMaxResults(1).getResultList().isEmpty();
    }

    /**
     * The one line a slice unit calls before it maps a page: ask, and downgrade the answer if the
     * table has been repaired. Does nothing at all when no as-of context is open, so a live read
     * neither asks the question nor pays for it (B3).
     */
    public void markIfDrifted(Class<? extends HistoryRow> mirror, Instant t) {
        if (!AsOfContext.isActive()) return;
        if (anyDriftedAtOrBefore(mirror, t)) {
            AsOfContext.markInexact(note(mirror));
        }
    }

    /** Named so the reader knows WHICH table was repaired, not merely that something was (B3). */
    public String note(Class<? extends HistoryRow> mirror) {
        HistoryBinding binding = registry.forMirror(mirror);
        String table = binding == null ? mirror.getSimpleName() : binding.mirrorTable();
        return table + " holds a version the reconciler repaired rather than watched happen, so a"
                + " change to it may be dated from when it was noticed";
    }
}
