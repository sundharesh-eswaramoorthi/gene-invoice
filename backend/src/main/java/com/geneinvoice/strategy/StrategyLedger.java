package com.geneinvoice.strategy;

import com.geneinvoice.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;

/**
 * The durable strategy execution journal: run admission, atomic due-work claims, and the
 * failure/retry bookkeeping shared by scheduled runs, manual runs, and recipient deliveries.
 * The initial attempt plus exactly three retries at one-hour intervals means a plan or run
 * becomes terminal only once its attempt count reaches {@link #MAX_ATTEMPTS}.
 */
@Service
@RequiredArgsConstructor
public class StrategyLedger {

    /** Initial attempt plus three hourly retries. */
    public static final int MAX_ATTEMPTS = 4;
    public static final Duration RETRY_INTERVAL = Duration.ofHours(1);

    private final StrategyRunRepository runRepository;
    private final StrategyDeliveryPlanRepository planRepository;
    private final StrategyClock clock;

    /**
     * Admits one scheduled run keyed by strategy and business date. The database unique
     * constraint makes a concurrent duplicate lose admission with a
     * {@link org.springframework.dao.DataIntegrityViolationException}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StrategyRun insertScheduledRun(Long strategyId, String strategyTitle, LocalDate businessDate) {
        return runRepository.saveAndFlush(StrategyRun.builder()
                .strategyId(strategyId)
                .strategyTitle(strategyTitle)
                .trigger(RunTrigger.SCHEDULED)
                .businessDate(businessDate)
                .status(RunStatus.ACTIVE)
                .attemptCount(0)
                .build());
    }

    /** Admits a manual run for any saved strategy, regardless of its active state. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StrategyRun insertManualRun(NotificationStrategy strategy) {
        return runRepository.saveAndFlush(StrategyRun.builder()
                .strategyId(strategy.getId())
                .strategyTitle(strategy.getTitle())
                .trigger(RunTrigger.MANUAL)
                .businessDate(null)
                .status(RunStatus.ACTIVE)
                .attemptCount(0)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPlan(Long planId) {
        return planRepository.claimPlan(planId, PlanState.PENDING, PlanState.IN_PROGRESS) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimRun(Long runId) {
        return runRepository.claimRun(runId, RunStatus.RETRY_PENDING, RunStatus.ACTIVE) == 1;
    }

    /**
     * Records one failed delivery attempt: increments the attempt count and either schedules
     * the next hourly retry (state back to PENDING with nextAttemptAt + 1h) or, once the third
     * retry has failed, marks the plan terminally FAILED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StrategyDeliveryPlan recordPlanFailure(Long planId, String message) {
        StrategyDeliveryPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Delivery plan not found: " + planId));
        plan.setAttemptCount(plan.getAttemptCount() + 1);
        plan.setFailureMessage(truncate(message));
        if (plan.getAttemptCount() >= MAX_ATTEMPTS) {
            plan.setState(PlanState.FAILED);
        } else {
            plan.setState(PlanState.PENDING);
            plan.setNextAttemptAt(clock.now().plus(RETRY_INTERVAL));
        }
        return planRepository.save(plan);
    }

    /** Records one failed run attempt with the same three-hourly-retry lifecycle. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StrategyRun recordRunFailure(Long runId, String message) {
        StrategyRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Strategy run not found: " + runId));
        run.setAttemptCount(run.getAttemptCount() + 1);
        run.setFailureMessage(truncate(message));
        if (run.getAttemptCount() >= MAX_ATTEMPTS) {
            run.setStatus(RunStatus.FAILED);
        } else {
            run.setStatus(RunStatus.RETRY_PENDING);
            run.setNextAttemptAt(clock.now().plus(RETRY_INTERVAL));
        }
        return runRepository.save(run);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 900 ? message : message.substring(0, 900);
    }
}
