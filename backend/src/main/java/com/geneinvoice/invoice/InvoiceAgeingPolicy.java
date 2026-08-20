package com.geneinvoice.invoice;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure, side-effect-free ageing policy for invoices.
 *
 * <p>Reached only through the static {@link #decide(Instant, BigDecimal, BigDecimal, Instant)}
 * method: every input arrives as a parameter, nothing is read from storage,
 * no clock is consulted and nothing is sent.
 */
public final class InvoiceAgeingPolicy {

    private static final long FIRST_OVERDUE_PERIODS = 30L;
    private static final long PERIODS_PER_STEP = 15L;

    private InvoiceAgeingPolicy() {
    }

    /**
     * Decides whether the invoice is overdue and which reminder step is due.
     *
     * <p>An invoice is outstanding while {@code amountPaid < total} (by
     * {@link BigDecimal#compareTo}); a fully paid or over-paid invoice is never
     * overdue. Age is measured in complete 24-hour periods between
     * {@code invoiceDate} and {@code now} (Duration-based, UTC-invariant).
     * Fewer than 30 complete periods is not overdue; at 30 or more the invoice
     * is overdue with {@code dueStep = 1 + floor((completePeriods - 30) / 15)}.
     */
    public static AgeingDecision decide(Instant invoiceDate, BigDecimal total, BigDecimal amountPaid, Instant now) {
        if (amountPaid.compareTo(total) >= 0) {
            return new AgeingDecision(false, 0);
        }
        long completePeriods = Duration.between(invoiceDate, now).toDays();
        if (completePeriods < FIRST_OVERDUE_PERIODS) {
            return new AgeingDecision(false, 0);
        }
        int dueStep = (int) (1L + (completePeriods - FIRST_OVERDUE_PERIODS) / PERIODS_PER_STEP);
        return new AgeingDecision(true, dueStep);
    }
}
