package com.geneinvoice.approval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Forgets applied changes older than the longest cumulative window (§3.8): nothing reads them any more. Once a
 * day, on a scheduler thread of its own (§5.15).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalHousekeeping {

    /** The longest window a threshold may have, in hours (30 days). */
    public static final int MAX_WINDOW_HOURS = 720;

    private final ApprovalUsageRepository usage;
    private final Clock clock;

    @Scheduled(cron = "${app.approval.housekeeping-cron:0 15 3 * * *}", zone = "UTC")
    public void run() {
        try {
            int purged = purge();
            if (purged > 0) log.info("approval.usage.purged count={}", purged);
        } catch (RuntimeException e) {
            log.error("approval.housekeeping.failed", e);
        }
    }

    /** Deletes the rows no window can reach; returns how many. */
    public int purge() {
        Instant before = clock.instant().minus(MAX_WINDOW_HOURS, ChronoUnit.HOURS);
        return usage.deleteUsedBefore(before);
    }
}
