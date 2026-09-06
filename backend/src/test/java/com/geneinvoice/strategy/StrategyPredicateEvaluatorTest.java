package com.geneinvoice.strategy;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of the predicate semantics the criteria demand: BETWEEN includes both endpoints for
 * dates (AC4 / EDGE1) and for amounts (AC4), every configured predicate combines with AND and
 * status membership gates before predicates (AC3), and invoice dates are interpreted on the
 * configured business calendar rather than the server default zone.
 */
class StrategyPredicateEvaluatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private final StrategyPredicateEvaluator evaluator = new StrategyPredicateEvaluator();

    // ------------------------------------------------------------ date operators

    @Test
    void dateBetweenIncludesBothEndpointsAndExcludesOutside() {
        LocalDate from = LocalDate.of(2024, 1, 10);
        LocalDate to = LocalDate.of(2024, 1, 20);
        assertTrue(evaluator.matchesDate(LocalDate.of(2024, 1, 10), DateOperator.BETWEEN, from, to),
                "the lower endpoint is included");
        assertTrue(evaluator.matchesDate(LocalDate.of(2024, 1, 20), DateOperator.BETWEEN, from, to),
                "the upper endpoint is included");
        assertTrue(evaluator.matchesDate(LocalDate.of(2024, 1, 15), DateOperator.BETWEEN, from, to));
        assertFalse(evaluator.matchesDate(LocalDate.of(2024, 1, 9), DateOperator.BETWEEN, from, to));
        assertFalse(evaluator.matchesDate(LocalDate.of(2024, 1, 21), DateOperator.BETWEEN, from, to));
    }

    @Test
    void dateBeforeOnAfterAreStrict() {
        LocalDate bound = LocalDate.of(2024, 1, 10);
        assertTrue(evaluator.matchesDate(LocalDate.of(2024, 1, 9), DateOperator.BEFORE, bound, null));
        assertFalse(evaluator.matchesDate(bound, DateOperator.BEFORE, bound, null));
        assertFalse(evaluator.matchesDate(LocalDate.of(2024, 1, 11), DateOperator.BEFORE, bound, null));

        assertTrue(evaluator.matchesDate(bound, DateOperator.ON, bound, null));
        assertFalse(evaluator.matchesDate(LocalDate.of(2024, 1, 9), DateOperator.ON, bound, null));

        assertTrue(evaluator.matchesDate(LocalDate.of(2024, 1, 11), DateOperator.AFTER, bound, null));
        assertFalse(evaluator.matchesDate(bound, DateOperator.AFTER, bound, null));
    }

    // ------------------------------------------------------------ amount operators

    @Test
    void amountBetweenIncludesBothEndpoints() {
        BigDecimal from = new BigDecimal("1000.00");
        BigDecimal to = new BigDecimal("2000.00");
        assertTrue(evaluator.matchesAmount(new BigDecimal("1000.00"), AmountOperator.BETWEEN, from, to),
                "the lower endpoint is included");
        assertTrue(evaluator.matchesAmount(new BigDecimal("2000.00"), AmountOperator.BETWEEN, from, to),
                "the upper endpoint is included");
        assertFalse(evaluator.matchesAmount(new BigDecimal("999.99"), AmountOperator.BETWEEN, from, to));
        assertFalse(evaluator.matchesAmount(new BigDecimal("2000.01"), AmountOperator.BETWEEN, from, to));
    }

    @Test
    void amountOperatorsCompareNumericallyNotByScale() {
        BigDecimal bound = new BigDecimal("1000.00");
        // compareTo semantics: 1000.0 and 1000.00 are equal amounts.
        assertTrue(evaluator.matchesAmount(new BigDecimal("1000.0"), AmountOperator.EQUAL, bound, null));
        assertTrue(evaluator.matchesAmount(new BigDecimal("999.99"), AmountOperator.LESS_THAN, bound, null));
        assertFalse(evaluator.matchesAmount(bound, AmountOperator.LESS_THAN, bound, null));
        assertTrue(evaluator.matchesAmount(new BigDecimal("1000.01"), AmountOperator.GREATER_THAN, bound, null));
        assertFalse(evaluator.matchesAmount(bound, AmountOperator.GREATER_THAN, bound, null));
    }

    // ------------------------------------------------------------ full invoice evaluation

    private static Invoice invoice(long id, InvoiceStatus status, String date, String total) {
        return Invoice.builder()
                .id(id)
                .invoiceNumber("INV-" + id)
                .customer(Customer.builder().id(10L).build())
                .invoiceDate(Instant.parse(date))
                .total(new BigDecimal(total))
                .status(status)
                .build();
    }

    private static NotificationStrategy strategy(DateOperator dateOp, LocalDate dateFrom, LocalDate dateTo,
                                                 AmountOperator amountOp, String amountFrom, String amountTo) {
        return NotificationStrategy.builder()
                .id(1L)
                .title("Overdue large invoices")
                .statuses(Set.of(InvoiceStatus.UNPAID, InvoiceStatus.PARTIALLY_PAID))
                .dateOperator(dateOp).dateFrom(dateFrom).dateTo(dateTo)
                .amountOperator(amountOp)
                .amountFrom(new BigDecimal(amountFrom))
                .amountTo(amountTo == null ? null : new BigDecimal(amountTo))
                .build();
    }

    @Test
    void invoiceIncludedExactlyWhenStatusAndEveryPredicateHold() {
        NotificationStrategy s = strategy(DateOperator.BETWEEN,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                AmountOperator.GREATER_THAN, "1000.00", null);

        Invoice in = invoice(1L, InvoiceStatus.UNPAID, "2024-01-15T12:00:00Z", "1500.00");
        Invoice wrongStatus = invoice(2L, InvoiceStatus.CANCELLED, "2024-01-15T12:00:00Z", "1500.00");
        Invoice dateMiss = invoice(3L, InvoiceStatus.UNPAID, "2024-02-01T12:00:00Z", "1500.00");
        Invoice amountMiss = invoice(4L, InvoiceStatus.UNPAID, "2024-01-15T12:00:00Z", "1000.00");
        Invoice amountEndpoint = invoice(5L, InvoiceStatus.PARTIALLY_PAID, "2024-01-15T12:00:00Z", "1000.01");
        Invoice dateEndpoint = invoice(6L, InvoiceStatus.UNPAID, "2024-01-31T12:00:00Z", "5000.00");

        assertTrue(evaluator.matches(in, s, UTC));
        assertFalse(evaluator.matches(wrongStatus, s, UTC), "status outside the selection excludes");
        assertFalse(evaluator.matches(dateMiss, s, UTC), "every configured predicate must hold");
        assertFalse(evaluator.matches(amountMiss, s, UTC), "GREATER_THAN is strict");
        assertTrue(evaluator.matches(amountEndpoint, s, UTC));
        assertTrue(evaluator.matches(dateEndpoint, s, UTC), "a date on the BETWEEN endpoint counts (UTC day)");
    }

    @Test
    void invoiceInstantIsReadOnTheConfiguredBusinessCalendar() {
        NotificationStrategy on10th = strategy(DateOperator.ON,
                LocalDate.of(2024, 1, 10), null,
                AmountOperator.GREATER_THAN, "0.00", null);
        Invoice inv = invoice(1L, InvoiceStatus.UNPAID, "2024-01-10T23:30:00Z", "50.00");

        assertTrue(evaluator.matches(inv, on10th, UTC), "23:30 UTC is still the 10th in UTC");
        assertFalse(evaluator.matches(inv, on10th, ZoneId.of("Australia/Sydney")),
                "the same instant is already the 11th in the Sydney business calendar");
        NotificationStrategy on11th = strategy(DateOperator.ON,
                LocalDate.of(2024, 1, 11), null,
                AmountOperator.GREATER_THAN, "0.00", null);
        assertTrue(evaluator.matches(inv, on11th, ZoneId.of("Australia/Sydney")));
    }
}
