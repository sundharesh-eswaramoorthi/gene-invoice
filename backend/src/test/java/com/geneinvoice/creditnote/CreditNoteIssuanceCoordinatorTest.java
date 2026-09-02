package com.geneinvoice.creditnote;

import com.geneinvoice.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The coordinator sequences: proxied transactional issue command FIRST (which
 * commits the credit note, the balance effect and the invoice audit entry on
 * normal return), then the post-commit admin notification consequence.
 */
class CreditNoteIssuanceCoordinatorTest {

    private final CreditNoteCommandService commandService = mock(CreditNoteCommandService.class);
    private final CreditNoteAdminNotifier adminNotifier = mock(CreditNoteAdminNotifier.class);
    private final CreditNoteIssuanceCoordinator coordinator =
            new CreditNoteIssuanceCoordinator(commandService, adminNotifier);

    private static final CreditNoteDtos.IssueCreditNoteRequest REQ =
            new CreditNoteDtos.IssueCreditNoteRequest(new BigDecimal("30.00"), "Overcharged");

    private static final CreditNoteDtos.CreditNoteResponse COMMITTED =
            new CreditNoteDtos.CreditNoteResponse(501L, 42L, "INV-0042", new BigDecimal("30.00"),
                    "Overcharged", "manager1", Instant.parse("2024-05-04T08:00:00Z"),
                    CreditNoteStatus.ACTIVE, null);

    @Test
    void notificationFailureReturnsCommittedNoteWithWarningAndNeverRetries() {
        when(commandService.issue(42L, REQ)).thenReturn(COMMITTED);
        when(adminNotifier.notifyAdminsOfIssuance(COMMITTED)).thenReturn(true);

        CreditNoteDtos.CreditNoteResponse res = coordinator.issue(42L, REQ);

        // Success-with-warning: the exact warning text the caller receives.
        assertEquals("Credit note issued, but at least one admin notification could not be delivered",
                res.warning());
        // The committed credit note is returned intact — nothing is rolled back.
        assertEquals(501L, res.id());
        assertEquals(42L, res.invoiceId());
        assertEquals(new BigDecimal("30.00"), res.amount());
        assertEquals("Overcharged", res.reason());
        assertEquals("manager1", res.issuedBy());
        assertEquals(CreditNoteStatus.ACTIVE, res.status());
        // Exactly one fan-out for the one committed issuance: no retry, no queue.
        verify(adminNotifier, times(1)).notifyAdminsOfIssuance(COMMITTED);
        verifyNoMoreInteractions(adminNotifier);
    }

    @Test
    void successfulNotificationReturnsCommittedNoteWithoutWarning() {
        when(commandService.issue(42L, REQ)).thenReturn(COMMITTED);
        when(adminNotifier.notifyAdminsOfIssuance(COMMITTED)).thenReturn(false);

        CreditNoteDtos.CreditNoteResponse res = coordinator.issue(42L, REQ);

        assertSame(COMMITTED, res);
        assertNull(res.warning());
        verify(adminNotifier, times(1)).notifyAdminsOfIssuance(COMMITTED);
    }

    @Test
    void preCommitCommandFailurePropagatesAndSkipsNotification() {
        when(commandService.issue(42L, REQ))
                .thenThrow(new BadRequestException("Credit note amount exceeds the remaining creditable amount of 75.00"));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> coordinator.issue(42L, REQ));

        assertEquals("Credit note amount exceeds the remaining creditable amount of 75.00", ex.getMessage());
        // Nothing committed, so no notification consequence is attempted at all.
        verifyNoInteractions(adminNotifier);
    }

    @Test
    void coordinatorHasNoTransactionalBoundaryAndCommandDoes() throws Exception {
        // The coordinator must stay non-transactional: only then does the proxied
        // command commit before notification starts, so a notification failure can
        // never roll back the committed note, balance or audit entry.
        assertFalse(CreditNoteIssuanceCoordinator.class.isAnnotationPresent(Transactional.class));
        for (Method m : CreditNoteIssuanceCoordinator.class.getDeclaredMethods()) {
            assertFalse(m.isAnnotationPresent(Transactional.class),
                    "coordinator method must not be transactional: " + m.getName());
        }
        assertFalse(CreditNoteAdminNotifier.class.isAnnotationPresent(Transactional.class));

        Method issue = CreditNoteCommandService.class
                .getMethod("issue", Long.class, CreditNoteDtos.IssueCreditNoteRequest.class);
        assertTrue(issue.isAnnotationPresent(Transactional.class));
        assertTrue(CreditNoteCommandService.class
                .getMethod("voidNote", Long.class, Long.class).isAnnotationPresent(Transactional.class));
    }
}
