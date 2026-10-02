package com.geneinvoice.automation.run;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.schedule.ScheduleTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Drives the consumer (§5.10.6): a fixed-delay poll, which never overlaps itself, drains what is due, and a
 * fixed-delay tick queues the scheduled rules' slots. The tick runs while the consumer is off too, so no slot is
 * lost; its events wait. A failure is logged and the next round tries again. Tests drive
 * {@link AutomationWorker#drain} and {@link ScheduleTicker#tick} directly (the intervals are an hour there).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationScheduler {

    private final AutomationWorker worker;
    private final ScheduleTicker ticker;
    private final AutomationProperties props;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.automation.poll-interval-ms:2000}",
            initialDelayString = "${app.automation.poll-interval-ms:2000}")
    public void poll() {
        if (!props.isConsumerEnabled()) return;
        try {
            worker.drain(clock.instant());
        } catch (RuntimeException e) {
            log.warn("automation.poll.failed: {}", e.toString(), e);
        }
    }

    @Scheduled(fixedDelayString = "${app.automation.tick-interval-ms:30000}",
            initialDelayString = "${app.automation.tick-interval-ms:30000}")
    public void tick() {
        try {
            ticker.tick(clock.instant());
        } catch (RuntimeException e) {
            log.warn("automation.tick.failed: {}", e.toString(), e);
        }
    }
}
