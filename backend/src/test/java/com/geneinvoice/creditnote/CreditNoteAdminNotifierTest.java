package com.geneinvoice.creditnote;

import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The post-commit fan-out attempts exactly one notification per admin with link
 * '/invoices' (an existing route), continues later recipients after one failure,
 * and never retries. Failures are only REPORTED to the caller.
 */
class CreditNoteAdminNotifierTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final CreditNoteAdminNotifier notifier =
            new CreditNoteAdminNotifier(userRepository, notificationService);

    private static final CreditNoteDtos.CreditNoteResponse NOTE =
            new CreditNoteDtos.CreditNoteResponse(501L, 42L, "INV-0042", new BigDecimal("30.00"),
                    "Overcharged", "manager1", Instant.parse("2024-05-04T08:00:00Z"),
                    CreditNoteStatus.ACTIVE, null);

    private static final User ADMIN1 = User.builder().id(11L).username("admin1").build();
    private static final User ADMIN2 = User.builder().id(12L).username("admin2").build();

    @Test
    void everyAdminGetsOneAttemptWithInvoiceListLink() {
        when(userRepository.findByRoleName("ADMIN")).thenReturn(List.of(ADMIN1, ADMIN2));

        boolean failed = notifier.notifyAdminsOfIssuance(NOTE);

        assertFalse(failed);
        verify(notificationService, times(1)).notify(eq(11L), eq("CREDIT_NOTE_ISSUED"),
                eq("Credit note issued"),
                eq("Credit note of 30.00 issued for invoice INV-0042 by manager1."),
                eq("/invoices"));
        verify(notificationService, times(1)).notify(eq(12L), eq("CREDIT_NOTE_ISSUED"),
                eq("Credit note issued"),
                eq("Credit note of 30.00 issued for invoice INV-0042 by manager1."),
                eq("/invoices"));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void failedRecipientDoesNotStopLaterAttemptsAndIsReportedOnceWithoutRetry() {
        when(userRepository.findByRoleName("ADMIN")).thenReturn(List.of(ADMIN1, ADMIN2));
        doThrow(new RuntimeException("notification store unavailable"))
                .when(notificationService)
                .notify(eq(11L), eq("CREDIT_NOTE_ISSUED"), eq("Credit note issued"),
                        eq("Credit note of 30.00 issued for invoice INV-0042 by manager1."),
                        eq("/invoices"));

        boolean failed = notifier.notifyAdminsOfIssuance(NOTE);

        assertTrue(failed);
        // The second admin is still attempted exactly once, and the failed admin
        // is attempted exactly once: no automatic retry, no queue.
        verify(notificationService, times(1)).notify(eq(11L), eq("CREDIT_NOTE_ISSUED"),
                eq("Credit note issued"),
                eq("Credit note of 30.00 issued for invoice INV-0042 by manager1."),
                eq("/invoices"));
        verify(notificationService, times(1)).notify(eq(12L), eq("CREDIT_NOTE_ISSUED"),
                eq("Credit note issued"),
                eq("Credit note of 30.00 issued for invoice INV-0042 by manager1."),
                eq("/invoices"));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void adminEnumerationFailureIsReportedWithoutAnyAttempt() {
        when(userRepository.findByRoleName("ADMIN"))
                .thenThrow(new RuntimeException("user store unavailable"));

        boolean failed = notifier.notifyAdminsOfIssuance(NOTE);

        assertTrue(failed);
        verifyNoInteractions(notificationService);
    }
}
