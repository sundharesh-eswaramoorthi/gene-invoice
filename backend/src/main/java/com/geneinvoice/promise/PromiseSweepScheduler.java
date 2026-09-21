package com.geneinvoice.promise;

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
            promiseService.sweepOverdue();
        } catch (RuntimeException e) {
            log.warn("Promise sweep failed: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }
}
