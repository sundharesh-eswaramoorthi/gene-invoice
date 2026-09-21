package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.time.LocalDate;
import java.util.List;

public record DateRange(LocalDate from, LocalDate toInclusive) {

    public static final List<String> PRESETS =
            List.of("today", "yesterday", "last7Days", "last30Days", "thisMonth", "lastMonth",
                    "thisYear", "past", "future");

    public static DateRange preset(String name, LocalDate today) {
        return switch (name == null ? "" : name.trim()) {
            case "today" -> new DateRange(today, today);
            case "yesterday" -> new DateRange(today.minusDays(1), today.minusDays(1));
            case "last7Days" -> new DateRange(today.minusDays(6), today);
            case "last30Days" -> new DateRange(today.minusDays(29), today);
            case "thisMonth" -> new DateRange(today.withDayOfMonth(1), today);
            case "lastMonth" -> {
                LocalDate firstOfLast = today.withDayOfMonth(1).minusMonths(1);
                yield new DateRange(firstOfLast, firstOfLast.plusMonths(1).minusDays(1));
            }
            case "thisYear" -> new DateRange(today.withDayOfYear(1), today);
            case "past" -> new DateRange(null, today.minusDays(1));
            case "future" -> new DateRange(today.plusDays(1), null);
            default -> throw new BadRequestException(
                    "Unknown date preset: " + name + " (expected one of " + PRESETS + ")");
        };
    }
}
