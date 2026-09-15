package com.geneinvoice.promise;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flips overdue promises to BROKEN on a timer rather than when someone happens to open a screen,
 * so filters and totals are correct for a user who never visits the record (AC-B5).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromiseSweepScheduler {

    private final PaymentPromiseService promiseService;

    /** A promised date has day granularity, so a quarter-hourly sweep is comfortably ahead of it. */
    @Scheduled(fixedDelayString = "${app.promises.sweep-interval-ms:900000}",
            initialDelayString = "${app.promises.sweep-interval-ms:900000}")
    public void sweep() {
        try {
            promiseService.sweepOverdue();
        } catch (RuntimeException e) {
            log.warn("Promise sweep failed: {}", e.getMessage());
        }
    }

    /** Catches anything that fell due while the service was down. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }
}
