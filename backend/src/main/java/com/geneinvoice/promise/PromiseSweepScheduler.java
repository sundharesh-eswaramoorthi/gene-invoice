package com.geneinvoice.promise;

import com.geneinvoice.region.RegionScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromiseSweepScheduler {

    private final PaymentPromiseService promiseService;

    @Scheduled(fixedDelayString = "${app.promises.sweep-interval-ms:900000}",
            initialDelayString = "${app.promises.sweep-interval-ms:900000}")
    public void sweep() {
        try {
            // Nobody is signed in on the scheduler's thread, and "nobody" reads as no regions at
            // all rather than as every region (the deliberate safe direction in RegionScope). A
            // sweep that marked only the promises of whoever happened to be logged in would be
            // worse than useless, so it names why it is entitled to the whole company (B1).
            // A block body, so the int sweepOverdue returns cannot make the Runnable and Supplier
            // overloads of asSystem ambiguous.
            RegionScope.asSystem(RegionScope.SystemReason.PROMISE_SWEEP,
                    () -> { promiseService.sweepOverdue(); });
        } catch (RuntimeException e) {
            log.warn("Promise sweep failed: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }
}
