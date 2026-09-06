package com.geneinvoice.strategy;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The single execution pipeline shared by scheduled and manual admission: prepare the run's
 * frozen delivery plans, attempt each recipient's initial delivery, and reconcile persisted
 * due retries. Run-level and recipient-level failures are recorded by the ledger and retried
 * three times one hour apart; only when the final attempt fails does an all-admin in-app
 * alert go out.
 */
@Service
@RequiredArgsConstructor
public class StrategyExecutionEngine {

    /** Per strategy/customer/recipient envelope naming that customer's newly notifiable invoices. */
    public static final String TYPE_STRATEGY_MATCH = "STRATEGY_MATCH";
    /** Per-admin outcome when a run has no newly notifiable invoice group. */
    public static final String TYPE_ZERO_MATCH = "STRATEGY_ZERO_MATCH";
    /** All-admin alert after one recipient's delivery plan exhausted its retries. */
    public static final String TYPE_DELIVERY_FAILED = "STRATEGY_DELIVERY_FAILED";
    /** All-admin alert after a run itself exhausted its retries. */
    public static final String TYPE_RUN_FAILED = "STRATEGY_RUN_FAILED";

    private final NotificationStrategyRepository strategyRepository;
    private final StrategyRunRepository runRepository;
    private final StrategyDeliveryPlanRepository planRepository;
    private final StrategyLedger ledger;
    private final StrategyRunPlanner planner;
    private final StrategyDeliveryExecutor deliveryExecutor;
    private final NotificationService notificationService;
    private final StrategyClock clock;

    /**
     * Admits any saved strategy, including an inactive one, into the shared pipeline. The
     * saved active flag is never read as a predicate here and never written back.
     */
    public StrategyRun submitManualRun(Long strategyId) {
        NotificationStrategy strategy = strategyRepository.findById(strategyId)
                .orElseThrow(() -> new NotFoundException("Strategy not found"));
        StrategyRun run = ledger.insertManualRun(strategy);
        executeRun(run.getId());
        return runRepository.findById(run.getId())
                .orElseThrow(() -> new NotFoundException("Strategy run not found: " + run.getId()));
    }

    /** Executes one admitted run: plan the deliveries, then attempt each plan once. */
    public void executeRun(Long runId) {
        List<Long> planIds;
        try {
            planIds = planner.preparePlans(runId);
        } catch (RuntimeException ex) {
            StrategyRun run = ledger.recordRunFailure(runId, String.valueOf(ex.getMessage()));
            if (run.getStatus() == RunStatus.FAILED) {
                alertRunFailure(run);
            }
            return;
        }
        for (Long planId : planIds) {
            attemptDelivery(planId);
        }
    }

    /** Claims and performs one delivery attempt for a plan, recording failure for retry. */
    public void attemptDelivery(Long planId) {
        if (!ledger.claimPlan(planId)) {
            return;
        }
        try {
            deliveryExecutor.deliver(planId);
        } catch (RuntimeException ex) {
            StrategyDeliveryPlan plan =
                    ledger.recordPlanFailure(planId, String.valueOf(ex.getMessage()));
            if (plan.getState() == PlanState.FAILED) {
                notificationService.notifyAdmins(TYPE_DELIVERY_FAILED,
                        "Delivery failed for strategy \"" + plan.getStrategyTitle() + "\"",
                        "Delivery to one recipient failed after the initial attempt and three "
                                + "hourly retries: " + plan.getFailureMessage(),
                        null);
            }
        }
    }

    /** Polls persistent state for due recipient retries and due failed runs. The poller is a
     *  pure wake-up: every decision comes from persisted nextAttemptAt/claim state, so a
     *  restart never erases remaining attempts. */
    public void processDueRetries() {
        Instant now = clock.now();
        for (StrategyDeliveryPlan plan : planRepository
                .findByStateAndNextAttemptAtLessThanEqual(PlanState.PENDING, now)) {
            attemptDelivery(plan.getId());
        }
        for (StrategyRun run : runRepository
                .findByStatusAndNextAttemptAtLessThanEqual(RunStatus.RETRY_PENDING, now)) {
            if (ledger.claimRun(run.getId())) {
                executeRun(run.getId());
            }
        }
    }

    private void alertRunFailure(StrategyRun run) {
        notificationService.notifyAdmins(TYPE_RUN_FAILED,
                "Strategy run failed: " + run.getStrategyTitle(),
                "The run failed after the initial attempt and three hourly retries: "
                        + run.getFailureMessage(),
                null);
    }
}
