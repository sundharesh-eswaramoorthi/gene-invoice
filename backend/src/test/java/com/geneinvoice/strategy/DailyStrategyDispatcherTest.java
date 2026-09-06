package com.geneinvoice.strategy;

import com.geneinvoice.invoice.InvoiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proof of daily admission: every active strategy — and only active ones — is admitted once
 * per business date at the configured business-calendar time (AC7 / FR6), an already-admitted
 * date is never executed again, and a losing duplicate insert wins nothing (AC10 / AQ1).
 */
@ExtendWith(MockitoExtension.class)
class DailyStrategyDispatcherTest {

    @Mock
    private NotificationStrategyRepository strategyRepository;
    @Mock
    private StrategyRunRepository runRepository;
    @Mock
    private StrategyLedger ledger;
    @Mock
    private StrategyExecutionEngine engine;

    private static NotificationStrategy strategy(long id, boolean active) {
        return NotificationStrategy.builder()
                .id(id).title("S" + id)
                .statuses(Set.of(InvoiceStatus.UNPAID))
                .dateOperator(DateOperator.AFTER).dateFrom(LocalDate.of(2024, 1, 1))
                .amountOperator(AmountOperator.GREATER_THAN).amountFrom(new BigDecimal("1.00"))
                .active(active)
                .build();
    }

    private DailyStrategyDispatcher newDispatcher(Instant fixed, String zone) {
        return new DailyStrategyDispatcher(strategyRepository, runRepository, ledger, engine,
                new StrategyClock(Clock.fixed(fixed, ZoneId.of(zone)), "05:00", zone));
    }

    @Test
    void atTheConfiguredTimeEveryActiveStrategyIsAdmittedForTodayAndExecuted() {
        DailyStrategyDispatcher dispatcher = newDispatcher(
                Instant.parse("2024-06-01T05:00:00Z"), "UTC"); // exactly 05:00 business time
        NotificationStrategy activeA = strategy(3L, true);
        NotificationStrategy activeB = strategy(4L, true);
        when(strategyRepository.findByActiveTrue()).thenReturn(List.of(activeA, activeB));
        when(ledger.insertScheduledRun(anyLong(), any(String.class), any(LocalDate.class)))
                .thenAnswer(inv -> StrategyRun.builder()
                        .id(900L + (Long) inv.getArgument(0))
                        .strategyId(inv.getArgument(0))
                        .strategyTitle(inv.getArgument(1))
                        .trigger(RunTrigger.SCHEDULED)
                        .businessDate(inv.getArgument(2))
                        .status(RunStatus.ACTIVE).build());

        dispatcher.reconcileDailyRuns();

        verify(ledger).insertScheduledRun(eq(3L), eq("S3"), eq(LocalDate.of(2024, 6, 1)));
        verify(ledger).insertScheduledRun(eq(4L), eq("S4"), eq(LocalDate.of(2024, 6, 1)));
        verify(engine).executeRun(903L);
        verify(engine).executeRun(904L);
        // Inactive strategies never appear: the selection is findByActiveTrue() and nothing else.
        verify(strategyRepository).findByActiveTrue();
        verify(strategyRepository, never()).findAll();
    }

    @Test
    void beforeTheConfiguredTimeTheDueBusinessDateIsYesterdayForCatchUp() {
        DailyStrategyDispatcher dispatcher = newDispatcher(
                Instant.parse("2024-06-01T04:00:00Z"), "UTC");
        when(strategyRepository.findByActiveTrue()).thenReturn(List.of(strategy(3L, true)));
        when(ledger.insertScheduledRun(anyLong(), any(String.class), any(LocalDate.class)))
                .thenAnswer(inv -> StrategyRun.builder().id(1L)
                        .strategyId(inv.getArgument(0))
                        .trigger(RunTrigger.SCHEDULED)
                        .businessDate(inv.getArgument(2))
                        .status(RunStatus.ACTIVE).build());

        dispatcher.reconcileDailyRuns();

        // at 04:00 the due business date is still yesterday, so a missed window is caught up
        verify(ledger).insertScheduledRun(eq(3L), eq("S3"), eq(LocalDate.of(2024, 5, 31)));
    }

    @Test
    void businessTimeHonoursTheConfiguredTimezoneNotTheServerClock() {
        // 07:30 UTC is 03:30 in New York: not yet due there, so yesterday is reconciled.
        DailyStrategyDispatcher dispatcher = newDispatcher(
                Instant.parse("2024-06-01T07:30:00Z"), "America/New_York");
        when(strategyRepository.findByActiveTrue()).thenReturn(List.of(strategy(3L, true)));
        when(ledger.insertScheduledRun(anyLong(), any(String.class), any(LocalDate.class)))
                .thenAnswer(inv -> StrategyRun.builder().id(1L)
                        .strategyId(inv.getArgument(0))
                        .trigger(RunTrigger.SCHEDULED)
                        .businessDate(inv.getArgument(2))
                        .status(RunStatus.ACTIVE).build());

        dispatcher.reconcileDailyRuns();

        verify(ledger).insertScheduledRun(eq(3L), eq("S3"), eq(LocalDate.of(2024, 5, 31)));
    }

    @Test
    void anAlreadyPersistedRunForTheBusinessDateIsNeverInsertedOrExecutedAgain() {
        DailyStrategyDispatcher dispatcher = newDispatcher(
                Instant.parse("2024-06-01T05:30:00Z"), "UTC");
        when(strategyRepository.findByActiveTrue()).thenReturn(List.of(strategy(3L, true)));
        when(runRepository.existsByStrategyIdAndBusinessDate(3L, LocalDate.of(2024, 6, 1)))
                .thenReturn(true);

        dispatcher.reconcileDailyRuns();

        verify(ledger, never()).insertScheduledRun(anyLong(), any(String.class), any(LocalDate.class));
        verify(engine, never()).executeRun(anyLong());
    }

    @Test
    void aLosingConcurrentInsertMeansAdmissionBelongsToTheOtherInstance() {
        DailyStrategyDispatcher dispatcher = newDispatcher(
                Instant.parse("2024-06-01T05:30:00Z"), "UTC");
        when(strategyRepository.findByActiveTrue()).thenReturn(List.of(strategy(3L, true)));
        when(ledger.insertScheduledRun(eq(3L), eq("S3"), eq(LocalDate.of(2024, 6, 1))))
                .thenThrow(new DataIntegrityViolationException("uq_strategy_run_day"));

        dispatcher.reconcileDailyRuns();

        // only the transaction that created the run executes it
        verify(engine, never()).executeRun(anyLong());
    }
}
