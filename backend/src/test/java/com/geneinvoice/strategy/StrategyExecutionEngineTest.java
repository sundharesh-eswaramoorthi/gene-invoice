package com.geneinvoice.strategy;

import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Proof of the shared execution pipeline: initial delivery attempts per frozen plan,
 * failure recording with retry rather than alert (AC12), the all-admin alert only after the
 * final attempt fails (AC12), retry of a failed recipient despite another recipient consuming
 * the pairs (AC13 / FAIL2), and manual admission of an inactive strategy without ever writing
 * its active state (AC8 / FR7).
 */
@ExtendWith(MockitoExtension.class)
class StrategyExecutionEngineTest {

    private static final Instant NOW = Instant.parse("2024-06-01T05:00:00Z");

    @Mock
    private NotificationStrategyRepository strategyRepository;
    @Mock
    private StrategyRunRepository runRepository;
    @Mock
    private StrategyDeliveryPlanRepository planRepository;
    @Mock
    private StrategyLedger ledger;
    @Mock
    private StrategyRunPlanner planner;
    @Mock
    private StrategyDeliveryExecutor deliveryExecutor;
    @Mock
    private NotificationService notificationService;

    private StrategyExecutionEngine newEngine() {
        return new StrategyExecutionEngine(strategyRepository, runRepository, planRepository,
                ledger, planner, deliveryExecutor, notificationService,
                new StrategyClock(Clock.fixed(NOW, ZoneOffset.UTC), "05:00", "UTC"));
    }

    @Test
    void runPlansAreAttemptedOnceEachInOrderAfterPlanningCommits() {
        when(planner.preparePlans(50L)).thenReturn(List.of(1L, 2L, 3L));
        when(ledger.claimPlan(anyLong())).thenReturn(true);

        newEngine().executeRun(50L);

        InOrder order = inOrder(planner, deliveryExecutor);
        order.verify(planner).preparePlans(50L);
        order.verify(deliveryExecutor).deliver(1L);
        order.verify(deliveryExecutor).deliver(2L);
        order.verify(deliveryExecutor).deliver(3L);
        verify(ledger, never()).recordPlanFailure(anyLong(), anyString());
        verifyNoInteractions(notificationService);
    }

    @Test
    void lostPlanClaimMeansSomeoneElseDeliversAndWeDoNothing() {
        when(ledger.claimPlan(7L)).thenReturn(false);

        newEngine().attemptDelivery(7L);

        verifyNoInteractions(deliveryExecutor);
        verify(ledger, never()).recordPlanFailure(anyLong(), anyString());
    }

    @Test
    void failedDeliveryIsRecordedForRetryWithoutAlertingAdminsYet() {
        when(ledger.claimPlan(7L)).thenReturn(true);
        doThrow(new RuntimeException("notification store down")).when(deliveryExecutor).deliver(7L);
        StrategyDeliveryPlan scheduled = StrategyDeliveryPlan.builder()
                .id(7L).strategyId(3L).strategyTitle("T").recipientUserId(90L)
                .notifType("STRATEGY_MATCH").title("T")
                .state(PlanState.PENDING).attemptCount(1).build();
        when(ledger.recordPlanFailure(eq(7L), anyString())).thenReturn(scheduled);

        newEngine().attemptDelivery(7L);

        verify(ledger).recordPlanFailure(eq(7L), contains("notification store down"));
        verifyNoInteractions(notificationService);
        // no admin alert before all retries have failed
    }

    @Test
    void deliveryPlanFailingItsFinalAttemptAlertsEveryAdmin() {
        when(ledger.claimPlan(7L)).thenReturn(true);
        doThrow(new RuntimeException("still down")).when(deliveryExecutor).deliver(7L);
        StrategyDeliveryPlan terminal = StrategyDeliveryPlan.builder()
                .id(7L).strategyId(3L).strategyTitle("Overdue large invoices").recipientUserId(90L)
                .notifType("STRATEGY_MATCH").title("Overdue large invoices")
                .failureMessage("still down")
                .state(PlanState.FAILED).attemptCount(4).build();
        when(ledger.recordPlanFailure(eq(7L), anyString())).thenReturn(terminal);

        newEngine().attemptDelivery(7L);

        verify(notificationService).notifyAdmins(
                eq("STRATEGY_DELIVERY_FAILED"),
                eq("Delivery failed for strategy \"Overdue large invoices\""),
                contains("three hourly retries"),
                isNull());
    }

    @Test
    void runFailureIsRetriedAndOnlyTheFinalFailureAlertsAllAdmins() {
        RuntimeException boom = new RuntimeException("invoice store down");
        StrategyRun retried = StrategyRun.builder()
                .id(50L).strategyId(3L).strategyTitle("Overdue large invoices")
                .trigger(RunTrigger.MANUAL)
                .status(RunStatus.RETRY_PENDING).attemptCount(1).failureMessage("invoice store down")
                .nextAttemptAt(NOW.plusSeconds(3600)).build();
        StrategyRun terminal = StrategyRun.builder()
                .id(50L).strategyId(3L).strategyTitle("Overdue large invoices")
                .trigger(RunTrigger.MANUAL)
                .status(RunStatus.FAILED).attemptCount(4).failureMessage("invoice store down").build();
        when(planner.preparePlans(50L)).thenThrow(boom);
        when(ledger.recordRunFailure(eq(50L), anyString())).thenReturn(retried).thenReturn(terminal);

        // Initial attempt fails: recorded, retried later, no alert.
        newEngine().executeRun(50L);
        verifyNoInteractions(notificationService);

        // The attempt after the third retry fails: one in-app alert to all admins.
        newEngine().executeRun(50L);
        verify(notificationService).notifyAdmins(
                eq("STRATEGY_RUN_FAILED"),
                eq("Strategy run failed: Overdue large invoices"),
                contains("three hourly retries"),
                isNull());
    }

    @Test
    void frozenFailedRecipientStaysRetryableAfterPairsAreConsumed() {
        // The engine never re-reads consumption state for a frozen plan: the failed recipient's
        // plan goes through the same claim -> deliver -> record path no matter what the other
        // recipient consumed (AC13).
        when(ledger.claimPlan(11L)).thenReturn(true);
        StrategyDeliveryPlan scheduled = StrategyDeliveryPlan.builder()
                .id(11L).strategyId(3L).strategyTitle("T").recipientUserId(91L)
                .notifType("STRATEGY_MATCH").title("T")
                .state(PlanState.PENDING).attemptCount(2).build();
        doThrow(new RuntimeException("still failing")).when(deliveryExecutor).deliver(11L);
        when(ledger.recordPlanFailure(eq(11L), anyString())).thenReturn(scheduled);

        newEngine().attemptDelivery(11L);

        verify(deliveryExecutor).deliver(11L);
        verify(ledger).recordPlanFailure(eq(11L), contains("still failing"));
        verifyNoInteractions(notificationService);
    }


    @Test
    void manualRunAdmitsAnInactiveStrategyWithoutTouchingItsActiveState() {
        NotificationStrategy inactive = NotificationStrategy.builder()
                .id(9L).title("Dormant strategy")
                .statuses(Set.of(InvoiceStatus.UNPAID))
                .dateOperator(DateOperator.AFTER).dateFrom(LocalDate.of(2024, 1, 1))
                .amountOperator(AmountOperator.GREATER_THAN).amountFrom(new BigDecimal("1000.00"))
                .active(false)
                .build();
        when(strategyRepository.findById(9L)).thenReturn(Optional.of(inactive));
        StrategyRun run = StrategyRun.builder()
                .id(77L).strategyId(9L).strategyTitle("Dormant strategy")
                .trigger(RunTrigger.MANUAL)
                .status(RunStatus.COMPLETED).attemptCount(0).outcome("ZERO_MATCH").build();
        when(ledger.insertManualRun(inactive)).thenReturn(run);
        when(planner.preparePlans(77L)).thenReturn(List.of());
        when(runRepository.findById(77L)).thenReturn(Optional.of(run));

        StrategyRun receipt = newEngine().submitManualRun(9L);

        assertEquals(RunTrigger.MANUAL, receipt.getTrigger());
        assertEquals("ZERO_MATCH", receipt.getOutcome(), "its saved filters were evaluated");
        assertFalse(inactive.isActive(),
                "running an inactive strategy must not activate it (AC8)");
        verify(strategyRepository, never()).save(any());
    }

    @Test
    void dueRetryPollerClaimsBeforeExecutingAndHonoursLostClaims() {
        StrategyDeliveryPlan duePlan = StrategyDeliveryPlan.builder()
                .id(21L).strategyId(3L).strategyTitle("T").recipientUserId(90L)
                .notifType("STRATEGY_MATCH").title("T")
                .state(PlanState.PENDING).attemptCount(1)
                .nextAttemptAt(NOW.minusSeconds(60)).build();
        when(planRepository.findByStateAndNextAttemptAtLessThanEqual(PlanState.PENDING, NOW))
                .thenReturn(List.of(duePlan));
        StrategyRun dueRun = StrategyRun.builder()
                .id(30L).strategyId(3L).strategyTitle("T").trigger(RunTrigger.SCHEDULED)
                .businessDate(LocalDate.of(2024, 6, 1))
                .status(RunStatus.RETRY_PENDING).attemptCount(1)
                .nextAttemptAt(NOW.minusSeconds(60)).build();
        StrategyRun lostRun = StrategyRun.builder()
                .id(31L).strategyId(4L).strategyTitle("U").trigger(RunTrigger.SCHEDULED)
                .businessDate(LocalDate.of(2024, 6, 1))
                .status(RunStatus.RETRY_PENDING).attemptCount(1)
                .nextAttemptAt(NOW.minusSeconds(60)).build();
        when(runRepository.findByStatusAndNextAttemptAtLessThanEqual(RunStatus.RETRY_PENDING, NOW))
                .thenReturn(List.of(dueRun, lostRun));
        when(ledger.claimPlan(21L)).thenReturn(true);
        when(ledger.claimRun(30L)).thenReturn(true);
        when(ledger.claimRun(31L)).thenReturn(false);
        when(planner.preparePlans(30L)).thenReturn(List.of());

        newEngine().processDueRetries();

        verify(deliveryExecutor).deliver(21L);
        verify(planner).preparePlans(30L);
        verify(planner, never()).preparePlans(31L);
    }
}
