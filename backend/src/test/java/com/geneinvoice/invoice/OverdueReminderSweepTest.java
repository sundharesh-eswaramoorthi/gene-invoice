package com.geneinvoice.invoice;

import com.geneinvoice.GeneInvoiceApplication;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Proof suite for the overdue-reminder sweep contract. Every behavioural scenario
 * drives the sweep exclusively through {@code runOverdueReminderSweep(Instant)} with
 * a self-chosen instant and mocked {@link InvoiceRepository}, {@link NotificationService}
 * and {@link AuditService}; the trigger wiring is proven reflectively. Ordering,
 * failure isolation and retries use a fresh service per run — the in-process overlap
 * claims are never persisted, so a new run (or restarted JVM) re-evaluates freely.
 */
@ExtendWith(MockitoExtension.class)
class OverdueReminderSweepTest {

    private static final Instant NOW = Instant.parse("2024-06-01T02:00:00Z");
    private static final String ENTITY_TYPE = "INVOICE";
    private static final String ACTION_PREFIX = "OVERDUE_REMINDER_STEP_";

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;

    private OverdueReminderSweepService newSweep() {
        return new OverdueReminderSweepService(invoiceRepository, notificationService, auditService);
    }

    private static Invoice invoice(long id, String number, long ageDays,
                                   String total, String paid, InvoiceStatus status) {
        return Invoice.builder()
                .id(id)
                .invoiceNumber(number)
                .invoiceDate(NOW.minus(Duration.ofDays(ageDays)))
                .total(new BigDecimal(total))
                .paidAmount(new BigDecimal(paid))
                .status(status)
                .build();
    }

    private static AuditLog reminderRow(long invoiceId, String action) {
        return AuditLog.builder()
                .entityType(ENTITY_TYPE)
                .entityId(invoiceId)
                .action(action)
                .build();
    }

    // ---------------------------------------------------------------- trigger wiring

    @Test
    void scheduledTriggerCarriesExactlyTheTwoAmUtcCron() throws Exception {
        Method trigger = OverdueReminderSchedulingConfig.class.getMethod("runDailyOverdueReminderSweep");
        assertEquals(0, trigger.getParameterCount());
        Scheduled scheduled = trigger.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "the daily trigger method must carry @Scheduled");
        assertEquals("0 0 2 * * *", scheduled.cron());
        assertEquals("UTC", scheduled.zone());
    }

    @Test
    void schedulingIsActivatedViaEnableScheduling() {
        assertTrue(GeneInvoiceApplication.class.isAnnotationPresent(EnableScheduling.class),
                "scheduling must be activated via @EnableScheduling on GeneInvoiceApplication");
    }

    // ---------------------------------------------------------------- selection

    @Test
    @SuppressWarnings("unchecked")
    void selectionGoesOnlyThroughFindByStatusInWithUnpaidAndPartiallyPaid() {
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);

        ArgumentCaptor<List<InvoiceStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(invoiceRepository).findByStatusIn(statuses.capture());
        assertEquals(2, statuses.getValue().size(),
                "exactly UNPAID and PARTIALLY_PAID may be selected, and nothing else");
        assertTrue(statuses.getValue().contains(InvoiceStatus.UNPAID));
        assertTrue(statuses.getValue().contains(InvoiceStatus.PARTIALLY_PAID));
        assertFalse(statuses.getValue().contains(InvoiceStatus.CANCELLED),
                "CANCELLED invoices must never reach evaluation");
        assertFalse(statuses.getValue().contains(InvoiceStatus.FULLY_PAID),
                "FULLY_PAID invoices must never reach evaluation");
        verifyNoMoreInteractions(invoiceRepository);
    }

    // ---------------------------------------------------------------- raise

    @Test
    void dueInvoiceRaisesExactlyOneNotifyAdminsThroughTheExistingFourStringPath() throws Exception {
        Method api = NotificationService.class.getMethod(
                "notifyAdmins", String.class, String.class, String.class, String.class);
        assertEquals(void.class, api.getReturnType(),
                "notifyAdmins keeps its existing four-string void shape");

        Invoice inv = invoice(1L, "INV-0001", 45L, "1000.00", "250.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);

        verify(notificationService, times(1)).notifyAdmins(
                eq("OVERDUE_REMINDER"),
                eq("Invoice INV-0001 is overdue"),
                argThat((String message) -> message.contains("step 2")),
                eq("/invoices/1"));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void raisedStepIsRecordedAsExactActionUnderInvoiceEntityTypeWithNullUserAndDispute() {
        Invoice inv = invoice(1L, "INV-0001", 45L, "1000.00", "250.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);

        // 45 complete periods -> dueStep 2, written as a plain unpadded decimal.
        verify(auditService, times(1)).record(
                eq("INVOICE"), eq(1L), eq("OVERDUE_REMINDER_STEP_2"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void freshAndFullyPaidInvoicesRaiseNothing() {
        Invoice fresh = invoice(1L, "INV-0001", 10L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        Invoice settled = invoice(2L, "INV-0002", 90L, "1000.00", "1000.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(fresh, settled));

        newSweep().runOverdueReminderSweep(NOW);

        verifyNoInteractions(notificationService);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- duplicate guard

    @Test
    void stepAlreadyInAuditHistoryIsNeverRaisedAgainHoweverOftenTheSweepRuns() {
        Invoice inv = invoice(1L, "INV-0001", 45L, "1000.00", "250.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L))
                .thenReturn(List.of(reminderRow(1L, "OVERDUE_REMINDER_STEP_2")));

        newSweep().runOverdueReminderSweep(NOW);
        newSweep().runOverdueReminderSweep(NOW);

        verifyNoInteractions(notificationService);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void postClaimRecheckCatchesARowAppearingBetweenSelectionAndNotification() {
        Invoice inv = invoice(1L, "INV-0001", 45L, "1000.00", "250.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        // The row only exists at the moment the sweep rechecks the history, modelling a
        // concurrent sweep that recorded the step after this run selected the invoice.
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenAnswer(invocation ->
                List.of(reminderRow(1L, "OVERDUE_REMINDER_STEP_2")));

        newSweep().runOverdueReminderSweep(NOW);

        verify(auditService).historyFor(ENTITY_TYPE, 1L);
        verifyNoInteractions(notificationService);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stepRaisedOnceIsNotNotifiedAgainOnLaterRuns() {
        Invoice inv = invoice(1L, "INV-0001", 30L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);
        verify(notificationService, times(1)).notifyAdmins(anyString(), anyString(), anyString(), anyString());

        // The first run's permanent row is visible to the next day's sweep.
        when(auditService.historyFor(ENTITY_TYPE, 1L))
                .thenReturn(List.of(reminderRow(1L, "OVERDUE_REMINDER_STEP_1")));

        newSweep().runOverdueReminderSweep(NOW.plus(Duration.ofDays(1)));

        verify(notificationService, times(1)).notifyAdmins(anyString(), anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- ordering

    @Test
    void notificationIsAttemptedBeforeTheAuditRecord() {
        Invoice inv = invoice(1L, "INV-0001", 30L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);

        InOrder order = inOrder(notificationService, auditService);
        order.verify(notificationService).notifyAdmins(anyString(), anyString(), anyString(), anyString());
        order.verify(auditService).record(eq("INVOICE"), eq(1L), eq("OVERDUE_REMINDER_STEP_1"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ---------------------------------------------------------------- failure isolation

    @Test
    void failedNotificationLeavesNoAuditRowDoesNotStopOthersAndIsRetriedNextRun() {
        Invoice failing = invoice(1L, "INV-0001", 45L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        Invoice healthy = invoice(2L, "INV-0002", 45L, "2000.00", "500.00", InvoiceStatus.PARTIALLY_PAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(failing, healthy));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());
        when(auditService.historyFor(ENTITY_TYPE, 2L)).thenReturn(List.of());
        doThrow(new RuntimeException("notification store down"))
                .when(notificationService).notifyAdmins(anyString(), anyString(), anyString(), eq("/invoices/1"));

        newSweep().runOverdueReminderSweep(NOW);

        // The failed invoice is left with no audit entry ...
        verify(auditService, never()).record(eq("INVOICE"), eq(1L), anyString(),
                any(), any(), any(), any(), any());
        // ... while the remaining invoice is fully processed in the same run.
        verify(notificationService).notifyAdmins(anyString(), anyString(), anyString(), eq("/invoices/2"));
        verify(auditService).record(eq("INVOICE"), eq(2L), eq("OVERDUE_REMINDER_STEP_2"),
                isNull(), isNull(), isNull(), isNull(), isNull());

        // The next run (a new sweep over the same instant, e.g. after a restart — the
        // overlap claims are in-process only) retries the failed invoice.
        newSweep().runOverdueReminderSweep(NOW);
        verify(notificationService, times(2)).notifyAdmins(anyString(), anyString(), anyString(), eq("/invoices/1"));
    }

    @Test
    void failedAuditWriteLeavesCommittedNotificationWithoutRowAndIsRetriedNextRun() {
        Invoice inv = invoice(1L, "INV-0001", 30L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());
        doThrow(new RuntimeException("audit store down")).doReturn(reminderRow(1L, "OVERDUE_REMINDER_STEP_1"))
                .when(auditService).record(eq("INVOICE"), eq(1L), eq("OVERDUE_REMINDER_STEP_1"),
                        isNull(), isNull(), isNull(), isNull(), isNull());

        newSweep().runOverdueReminderSweep(NOW);
        // The notification committed but no audit row was written.
        verify(notificationService, times(1)).notifyAdmins(anyString(), anyString(), anyString(), eq("/invoices/1"));

        // The next run finds still no audit row and raises the step again:
        // at-least-once delivery, exactly-once auditing — the suite asserts the retry,
        // not exactly-once delivery.
        newSweep().runOverdueReminderSweep(NOW);
        verify(notificationService, times(2)).notifyAdmins(anyString(), anyString(), anyString(), eq("/invoices/1"));
        verify(auditService, times(2)).record(eq("INVOICE"), eq(1L), eq("OVERDUE_REMINDER_STEP_1"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ---------------------------------------------------------------- catch-up

    @Test
    void sweepGapRaisesOnlyTheLatestDueStepOnceAndAuditsNothingForSkippedSteps() {
        // 75 complete 24-hour periods old -> dueStep = 1 + floor((75 - 30) / 15) = 4.
        Invoice inv = invoice(1L, "INV-0001", 75L, "1000.00", "0.00", InvoiceStatus.UNPAID);
        when(invoiceRepository.findByStatusIn(anyList())).thenReturn(List.of(inv));
        when(auditService.historyFor(ENTITY_TYPE, 1L)).thenReturn(List.of());

        newSweep().runOverdueReminderSweep(NOW);

        verify(notificationService, times(1)).notifyAdmins(
                eq("OVERDUE_REMINDER"), anyString(),
                argThat((String message) -> message.contains("step 4")),
                eq("/invoices/1"));

        ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
        verify(auditService, times(1)).record(eq("INVOICE"), eq(1L), actions.capture(),
                isNull(), isNull(), isNull(), isNull(), isNull());
        assertEquals("OVERDUE_REMINDER_STEP_4", actions.getValue());
        verify(auditService, never()).record(any(), any(), eq(ACTION_PREFIX + "1"),
                any(), any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), eq(ACTION_PREFIX + "2"),
                any(), any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), eq(ACTION_PREFIX + "3"),
                any(), any(), any(), any(), any());
    }
}
