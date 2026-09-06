package com.geneinvoice.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Durable daily admission: for the currently due business date, every persisted active
 * strategy receives exactly one SCHEDULED run, database-enforced by the unique
 * strategy/business-date identity. Inactive strategies are never admitted. Only the caller
 * whose insert won executes the run; a losing tick or instance simply moves on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyStrategyDispatcher {

    private final NotificationStrategyRepository strategyRepository;
    private final StrategyRunRepository runRepository;
    private final StrategyLedger ledger;
    private final StrategyExecutionEngine engine;
    private final StrategyClock clock;

    public void reconcileDailyRuns() {
        LocalDate dueDate = clock.dueBusinessDate();
        for (NotificationStrategy strategy : strategyRepository.findByActiveTrue()) {
            if (runRepository.existsByStrategyIdAndBusinessDate(strategy.getId(), dueDate)) {
                continue;
            }
            try {
                StrategyRun run = ledger.insertScheduledRun(
                        strategy.getId(), strategy.getTitle(), dueDate);
                engine.executeRun(run.getId());
            } catch (DataIntegrityViolationException duplicate) {
                // Another tick or another application instance admitted this daily run first.
                log.debug("Strategy {} already admitted for {}", strategy.getId(), dueDate);
            }
        }
    }
}
