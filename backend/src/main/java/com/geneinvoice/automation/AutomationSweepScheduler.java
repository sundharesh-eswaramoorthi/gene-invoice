package com.geneinvoice.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fourth scheduled job, in the {@code EmailSweepScheduler} shape verbatim (A5).
 *
 * <p>THREE ARMS, THREE try/catch BLOCKS, on purpose: the sweep is the durability guarantee, the
 * tick is the clock, and the reaper is housekeeping, and one of them failing must never stop the
 * other two. Each logs at WARN and returns, which is what makes the next tick a recovery rather
 * than a repeat of the same crash.
 *
 * <p>The interval is declared in {@code application.yml} AND defaulted inline on the annotation,
 * so an installation with no {@code app.automation} block still sweeps.
 * {@code spring.task.scheduling.pool.size} is 5 and its comment already names this sweeper — the
 * Spring default is 1, so that line is load-bearing and this bean is the fourth job it sizes for.
 *
 * <p>There is no {@code cron} expression here or anywhere in this repository. See
 * {@link AutomationSchedules} for the three reasons (A1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationSweepScheduler {

    private final AutomationDispatcher dispatcher;
    private final AutomationSchedules schedules;
    private final AutomationRetention retention;

    @Scheduled(fixedDelayString = "${app.automation.sweep-interval-ms:60000}",
            initialDelayString = "${app.automation.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            dispatcher.sweep();
        } catch (RuntimeException e) {
            log.warn("Automation sweep failed: {}", e.getMessage());
        }
        try {
            schedules.tick();
        } catch (RuntimeException e) {
            log.warn("Automation schedule tick failed: {}", e.getMessage());
        }
        try {
            retention.sweep();
        } catch (RuntimeException e) {
            log.warn("Automation retention failed: {}", e.getMessage());
        }
    }
}
