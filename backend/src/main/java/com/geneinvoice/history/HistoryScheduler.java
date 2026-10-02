package com.geneinvoice.history;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs history drift in the background: records whose version capture was skipped (a mapping error in
 * production, where {@code app.history.strict} is false) or that SQL changed without naming them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HistoryScheduler {

    private final HistoryReconciler reconciler;

    @Scheduled(fixedDelayString = "${app.history.reconcile-interval-ms:86400000}",
            initialDelayString = "${app.history.reconcile-interval-ms:86400000}")
    public void reconcile() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException e) {
            log.error("History reconcile failed", e);
        }
    }
}
