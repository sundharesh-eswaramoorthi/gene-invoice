package com.geneinvoice.strategy;

import com.geneinvoice.invoice.Invoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Evaluates one persisted invoice against a strategy's saved date and amount predicates.
 * The authoritative values are used unchanged: the {@code invoiceDate} Instant is interpreted
 * as a business-calendar date in the configured business timezone, and the scale-two
 * {@code total} BigDecimal is compared with {@link BigDecimal#compareTo}. BETWEEN includes
 * both endpoints; every configured predicate must hold (logical AND).
 */
@Component
public class StrategyPredicateEvaluator {

    public boolean matches(Invoice invoice, NotificationStrategy strategy, ZoneId businessZone) {
        if (!strategy.getStatuses().contains(invoice.getStatus())) {
            return false;
        }
        LocalDate businessDate = invoice.getInvoiceDate().atZone(businessZone).toLocalDate();
        return matchesDate(businessDate, strategy.getDateOperator(),
                strategy.getDateFrom(), strategy.getDateTo())
                && matchesAmount(invoice.getTotal(), strategy.getAmountOperator(),
                strategy.getAmountFrom(), strategy.getAmountTo());
    }

    public boolean matchesDate(LocalDate value, DateOperator operator, LocalDate from, LocalDate to) {
        return switch (operator) {
            case BEFORE -> value.isBefore(from);
            case ON -> value.isEqual(from);
            case AFTER -> value.isAfter(from);
            case BETWEEN -> !value.isBefore(from) && !value.isAfter(to);
        };
    }

    public boolean matchesAmount(BigDecimal value, AmountOperator operator,
                                 BigDecimal from, BigDecimal to) {
        return switch (operator) {
            case LESS_THAN -> value.compareTo(from) < 0;
            case EQUAL -> value.compareTo(from) == 0;
            case GREATER_THAN -> value.compareTo(from) > 0;
            case BETWEEN -> value.compareTo(from) >= 0 && value.compareTo(to) <= 0;
        };
    }
}
