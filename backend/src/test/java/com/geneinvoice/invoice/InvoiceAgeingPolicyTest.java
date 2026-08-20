package com.geneinvoice.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link InvoiceAgeingPolicy}. Every input arrives as a
 * parameter to the static {@code decide} method: no Spring context, no
 * repository, no notification collaborator, no web layer — the suite proves
 * the policy is fully self-contained.
 */
class InvoiceAgeingPolicyTest {

    private static final Instant INVOICE_DATE = Instant.parse("2024-01-15T10:30:00Z");
    private static final BigDecimal TOTAL = new BigDecimal("1200.00");
    private static final BigDecimal PART_PAID = new BigDecimal("400.00");

    private static Instant after(Duration elapsed) {
        return INVOICE_DATE.plus(elapsed);
    }

    @Test
    void outstandingInvoiceIsNotOverdueBeforeThirtyCompletePeriods() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID, after(Duration.ofDays(29)));

        assertFalse(decision.overdue());
        assertEquals(0, decision.dueStep());
    }

    @Test
    void outstandingInvoiceIsOverdueWithStepOneAtExactlyThirtyCompletePeriods() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID, after(Duration.ofDays(30)));

        assertTrue(decision.overdue());
        assertEquals(1, decision.dueStep());
    }

    @Test
    void stepOneHoldsFromThirtyUpToJustBeforeFortyFiveCompletePeriods() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID, after(Duration.ofDays(44)));

        assertTrue(decision.overdue());
        assertEquals(1, decision.dueStep());
    }

    @Test
    void stepAdvancesToTwoAtExactlyFortyFiveCompletePeriods() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID, after(Duration.ofDays(45)));

        assertTrue(decision.overdue());
        assertEquals(2, decision.dueStep());
    }

    @Test
    void stepAdvancesToThreeAtExactlySixtyCompletePeriods() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID, after(Duration.ofDays(60)));

        assertTrue(decision.overdue());
        assertEquals(3, decision.dueStep());
    }

    @Test
    void ageIsCountedInCompleteTwentyFourHourPeriodsNotCalendarDates() {
        // 29 days and 23 hours after invoiceDate: one hour short of the
        // thirtieth complete 24-hour period, so still not overdue even
        // though 30 calendar dates have been touched.
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, PART_PAID,
                after(Duration.ofDays(29).plusHours(23)));

        assertFalse(decision.overdue());
        assertEquals(0, decision.dueStep());
    }

    @Test
    void fullyPaidInvoiceIsNeverOverdueRegardlessOfAge() {
        // Paid in full with a different scale: equality is judged by
        // BigDecimal.compareTo, so "1200.0" equals "1200.00".
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, new BigDecimal("1200.0"), after(Duration.ofDays(90)));

        assertFalse(decision.overdue());
        assertEquals(0, decision.dueStep());
    }

    @Test
    void overpaidInvoiceIsNeverOverdueRegardlessOfAge() {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                INVOICE_DATE, TOTAL, new BigDecimal("1200.01"), after(Duration.ofDays(90)));

        assertFalse(decision.overdue());
        assertEquals(0, decision.dueStep());
    }
}
