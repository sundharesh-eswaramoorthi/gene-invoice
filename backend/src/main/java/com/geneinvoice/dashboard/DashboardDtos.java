package com.geneinvoice.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class DashboardDtos {

    /** MIXED: the caller sees some regions in full and others only as their own book. */
    public enum Coverage { ALL, BOOK, OWN, MIXED }

    /**
     * Which regions a figure answers for (B1), and in which of them only the caller's own book counts;
     * {@code asOf} is the date viewed, or null when live.
     */
    public record ScopeInfo(List<Long> regionIds, List<Long> bookRegionIds, String asOf) {}

    public record MonthPoint(String month, BigDecimal amount, long count) {}

    public record MonthlySeries(Coverage coverage, List<MonthPoint> months, ScopeInfo scope) {}

    public record AgeBucket(String label, Integer fromDays, Integer toDays,
                            BigDecimal amount, long count,
                            LocalDate dueDateFrom, LocalDate dueDateTo) {}

    public record OutstandingByAge(Coverage coverage, List<AgeBucket> buckets, ScopeInfo scope) {}

    public record OutstandingCustomer(Long customerId, String customerName, BigDecimal outstanding,
                                      long openInvoices, Instant oldestInvoiceDate) {}

    public record TopOutstanding(Coverage coverage, List<OutstandingCustomer> customers, ScopeInfo scope) {}

    public record PayingCustomer(Long customerId, String customerName, BigDecimal collected,
                                 long payments, Instant lastPaidAt) {}

    public record TopPaying(Coverage coverage, List<PayingCustomer> customers, ScopeInfo scope) {}
}
