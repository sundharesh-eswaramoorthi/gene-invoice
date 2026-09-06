package com.geneinvoice.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proof of the failure-recovery ledger: the initial failure is recorded and retried three
 * times one hour apart (the explicit ruling behind FAIL1 / AC12), and a plan or run becomes
 * terminal only when the attempt after the third retry also fails.
 */
@ExtendWith(MockitoExtension.class)
class StrategyLedgerTest {

    private static final Instant NOW = Instant.parse("2024-06-01T05:00:00Z");

    @Mock
    private StrategyRunRepository runRepository;
    @Mock
    private StrategyDeliveryPlanRepository planRepository;

    private StrategyLedger newLedger() {
        return new StrategyLedger(runRepository, planRepository,
                new StrategyClock(Clock.fixed(NOW, ZoneOffset.UTC), "05:00", "UTC"));
    }

    private StrategyDeliveryPlan planAt(int attemptCount) {
        StrategyDeliveryPlan plan = StrategyDeliveryPlan.builder()
                .id(1L).runId(50L).strategyId(3L).strategyTitle("T")
                .recipientUserId(90L).notifType("STRATEGY_MATCH").title("T")
                .state(PlanState.PENDING).attemptCount(attemptCount).build();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return plan;
    }

    @Test
    void firstFailureIsRecordedAndRescheduledExactlyOneHourLater() {
        StrategyDeliveryPlan plan = planAt(0);

        StrategyDeliveryPlan after = newLedger().recordPlanFailure(1L, "boom");

        assertEquals(1, after.getAttemptCount(), "the initial attempt's failure is recorded");
        assertEquals(PlanState.PENDING, after.getState(), "the plan remains retryable");
        assertEquals(NOW.plus(Duration.ofHours(1)), after.getNextAttemptAt(),
                "the retry is exactly one hour after the failure");
        assertEquals("boom", after.getFailureMessage());
    }

    @Test
    void eachRetrySchedulesExactlyOneHourAfterItsFailureAndStaysPending() {
        planAt(2);

        StrategyDeliveryPlan afterThirdAttempt = newLedger().recordPlanFailure(1L, "boom");

        assertEquals(3, afterThirdAttempt.getAttemptCount());
        assertEquals(PlanState.PENDING, afterThirdAttempt.getState(),
                "after the second retry fails, the third retry is still pending");
        assertEquals(NOW.plus(Duration.ofHours(1)), afterThirdAttempt.getNextAttemptAt(),
                "every retry lands exactly one hour after its failure");
    }

    @Test
    void failureAfterTheThirdRetryMarksThePlanTerminallyFailed() {
        planAt(3);

        StrategyDeliveryPlan after = newLedger().recordPlanFailure(1L, "boom");

        assertEquals(4, after.getAttemptCount(),
                "attempts: initial + retry 1 + retry 2 + retry 3");
        assertEquals(PlanState.FAILED, after.getState(),
                "the plan is terminal only after the attempt following retry three fails");
    }

    @Test
    void runFailuresFollowTheSameThreeHourlyRetryLifecycle() {
        StrategyRun run = StrategyRun.builder()
                .id(9L).strategyId(3L).strategyTitle("T")
                .trigger(RunTrigger.SCHEDULED).businessDate(java.time.LocalDate.of(2024, 6, 1))
                .status(RunStatus.ACTIVE).attemptCount(0).build();
        when(runRepository.findById(9L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyRun first = newLedger().recordRunFailure(9L, "store down");
        assertEquals(1, first.getAttemptCount());
        assertEquals(RunStatus.RETRY_PENDING, first.getStatus());
        assertEquals(NOW.plus(Duration.ofHours(1)), first.getNextAttemptAt());

        run.setAttemptCount(3);
        StrategyRun terminal = newLedger().recordRunFailure(9L, "store down");
        assertEquals(4, terminal.getAttemptCount());
        assertEquals(RunStatus.FAILED, terminal.getStatus());
    }
}
