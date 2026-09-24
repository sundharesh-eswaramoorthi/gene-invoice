package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOfFloor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The day this installation started keeping history, published to the read side (B3).
 *
 * <p>Supplying this bean is all it takes: {@code AsOfInterceptor} and {@code AsOfController} both
 * hold {@link AsOfFloor} through an {@code ObjectProvider} precisely so that B3-UPGRADES could
 * arm the pre-floor branch of {@code AsOfDates.parse} and fill in
 * {@code GET /api/as-of}'s {@code floor} with no edit to either file (B3).
 *
 * <p>READ ONCE AND CACHED, because the floor is installed once and never moves: re-reading it per
 * request would put a select in front of every as-of answer to learn something that cannot have
 * changed since boot. A null is NOT cached — while no floor exists the table is empty and the
 * lookup is a primary-key hit, and re-reading lets an installation whose seed failed pick the
 * floor up on the next attempt instead of reporting "no history at all" until somebody restarts
 * it (B3).
 *
 * <p>THE DATE IS TAKEN AT UTC AND NOWHERE ELSE. This codebase is UTC-only — {@code
 * InvoiceDates.today()} is {@code LocalDate.now(ZoneOffset.UTC)} — and a date derived from an
 * instant through the JVM's or the database session's default zone is the bug a previous unit
 * shipped into the region backfill and had to fix. A floor a day out is a day of answers that
 * claim to be exact and are seeded, or the reverse (B3).
 */
@Component
@DependsOn("historySeedUpgrade")
@RequiredArgsConstructor
@Slf4j
class HistoryFloorService implements AsOfFloor, InitializingBean {

    private final HistoryFloorRepository repository;

    private volatile LocalDate floor;

    /**
     * Read at boot rather than on the first request, so the first as-of caller does not pay for
     * it and so the answer cannot depend on which request happened to be first (B3).
     */
    @Override
    public void afterPropertiesSet() {
        LocalDate known = floorOrNull();
        if (known == null) {
            log.warn("No history floor is installed; every as-of date will read as pre-floor (B3)");
        } else {
            log.info("History floor is {}", known);
        }
    }

    @Override
    public LocalDate floorOrNull() {
        LocalDate known = floor;
        if (known != null) return known;
        LocalDate read = repository.findById(HistoryFloor.SINGLETON)
                .map(row -> LocalDate.ofInstant(row.getInstalledAt(), ZoneOffset.UTC))
                .orElse(null);
        floor = read;
        return read;
    }
}
