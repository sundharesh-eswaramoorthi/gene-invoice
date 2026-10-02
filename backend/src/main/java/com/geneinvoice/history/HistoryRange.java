package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOfException;
import com.geneinvoice.common.scope.AsOfSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Which days history can answer for: from the day the first baseline ran (§5.7.9) up to yesterday, today being
 * the live view. Read from {@code history_meta} on every call, never cached: another instance's baseline, or a
 * test's reset, changes it under a running one (R10). It is what the view-scope interceptor asks before it binds a
 * past date (the only {@link AsOfSupport}).
 */
@Component
public class HistoryRange implements AsOfSupport {

    @PersistenceContext
    private EntityManager em;

    private final Clock clock;

    public HistoryRange(Clock clock) {
        this.clock = clock;
    }

    /** The first day history can be read for; empty when there is no lower bound (nothing predates history). */
    @Transactional(readOnly = true)
    public Optional<LocalDate> historyStartsOn() {
        HistoryMeta meta = em.find(HistoryMeta.class, HistoryMeta.ID);
        return Optional.ofNullable(meta)
                .map(HistoryMeta::getStartedAt)
                .map(started -> LocalDate.ofInstant(started, ZoneOffset.UTC));
    }

    /** The latest day that is history rather than the live view: yesterday (UTC). */
    public LocalDate latestAsOf() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
    }

    /** A day before history starts cannot be answered: 400 {@code AS_OF_BEFORE_HISTORY}. */
    @Override
    public void requireAvailable(LocalDate d) {
        Optional<LocalDate> start = historyStartsOn();
        if (start.isPresent() && d.isBefore(start.get())) throw AsOfException.beforeHistory(start.get());
    }
}
