package com.geneinvoice.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic wake-up for strategy work only. The tick reconciles due daily admissions and due
 * retry records from persistent state; it is never itself treated as proof that work ran —
 * every admission, attempt and outcome lives in the durable ledger, so a restart or a second
 * application instance reconciles the same durable records.
 */
@Configuration
@Slf4j
public class StrategySchedulingConfig {

    private final DailyStrategyDispatcher dispatcher;
    private final StrategyExecutionEngine engine;

    public StrategySchedulingConfig(DailyStrategyDispatcher dispatcher,
                                    StrategyExecutionEngine engine) {
        this.dispatcher = dispatcher;
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void reconcileDueStrategyWork() {
        try {
            dispatcher.reconcileDailyRuns();
        } catch (RuntimeException ex) {
            log.error("Daily strategy admission reconciliation failed", ex);
        }
        try {
            engine.processDueRetries();
        } catch (RuntimeException ex) {
            log.error("Strategy retry reconciliation failed", ex);
        }
    }
}
