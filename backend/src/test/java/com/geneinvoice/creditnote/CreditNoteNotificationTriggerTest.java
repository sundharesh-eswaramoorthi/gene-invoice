package com.geneinvoice.creditnote;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.notification.NotificationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC10 / AC11 / FAIL6: the controller is the post-commit boundary — the existing all-admin
 * fanout is invoked once after a committed issuance, its failure becomes success-with-warning
 * with no retry, and a refused issuance never reaches the fanout.
 */
class CreditNoteNotificationTriggerTest {

    private final CreditNoteService creditNoteService = mock(CreditNoteService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final CreditNoteController controller =
            new CreditNoteController(creditNoteService, notificationService);

    private static CreditNoteDtos.CreditNoteDto issuedNote() {
        return new CreditNoteDtos.CreditNoteDto(
                10L, 20L, "INV-20240101-0001",
                new BigDecimal("25.00"), "Damaged goods",
                7L, "System Administrator", Instant.parse("2024-01-01T10:00:00Z"),
                false);
    }

    private static CreditNoteDtos.IssueCreditNoteRequest request() {
        return new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("25.00"), "Damaged goods");
    }

    @Test
    void committedIssueTriggersTheAllAdminFanoutExactlyOnce() {
        when(creditNoteService.issue(eq(20L), any())).thenReturn(issuedNote());

        CreditNoteDtos.IssueCreditNoteResponse res = controller.issue(20L, request());

        assertThat(res.creditNote().amount()).isEqualByComparingTo("25.00");
        assertThat(res.notificationWarning()).isFalse();
        assertThat(res.notificationWarningMessage()).isNull();
        verify(notificationService, times(1)).notifyAdmins(
                eq("CREDIT_NOTE_ISSUED"),
                eq("Credit note issued on INV-20240101-0001"),
                contains("Damaged goods"),
                eq("/invoices"));
    }

    @Test
    void fanoutFailureBecomesCommittedSuccessWithWarningAndNoRetry() {
        when(creditNoteService.issue(eq(20L), any())).thenReturn(issuedNote());
        doThrow(new IllegalStateException("notification store down")).when(notificationService)
                .notifyAdmins(anyString(), anyString(), anyString(), anyString());

        CreditNoteDtos.IssueCreditNoteResponse res = controller.issue(20L, request());

        // the committed credit note is still returned as the outcome
        assertThat(res.creditNote().id()).isEqualTo(10L);
        assertThat(res.creditNote().voided()).isFalse();
        assertThat(res.notificationWarning()).isTrue();
        assertThat(res.notificationWarningMessage())
                .isEqualTo("Credit note issued, but admin notifications could not be delivered");
        // exactly one attempt: no retry, no queue
        verify(notificationService, times(1))
                .notifyAdmins(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void refusedIssueNeverReachesTheNotificationFanout() {
        when(creditNoteService.issue(eq(20L), any()))
                .thenThrow(new BadRequestException("Amount exceeds the amount still creditable for this invoice: 10.00"));

        assertThatThrownBy(() -> controller.issue(20L, request()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10.00");

        verifyNoInteractions(notificationService);
    }
}
