package com.geneinvoice.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class DashboardDtos {

    /**
     * Whose records a dashboard card covers, so the screen can say so: everything, the caller's own
     * POC book, or a customer login's own account.
     */
    public enum Coverage { ALL, BOOK, OWN }

    /** One calendar month (UTC), as "2026-09". */
    public record MonthPoint(String month, BigDecimal amount, long count) {}

    /** Oldest month first, every month present, empty ones as zero. */
    public record MonthlySeries(Coverage coverage, List<MonthPoint> months) {}

    /** Outstanding balances by days since the invoice date; {@code toDays} is null on the last bucket. */
    public record AgeBucket(String label, int fromDays, Integer toDays, BigDecimal amount, long count) {}

    public record OutstandingByAge(Coverage coverage, List<AgeBucket> buckets) {}

    public record OutstandingCustomer(Long customerId, String customerName, BigDecimal outstanding,
                                      long openInvoices, Instant oldestInvoiceDate) {}

    public record TopOutstanding(Coverage coverage, List<OutstandingCustomer> customers) {}

    public record PayingCustomer(Long customerId, String customerName, BigDecimal collected,
                                 long payments, Instant lastPaidAt) {}

    public record TopPaying(Coverage coverage, List<PayingCustomer> customers) {}
}
