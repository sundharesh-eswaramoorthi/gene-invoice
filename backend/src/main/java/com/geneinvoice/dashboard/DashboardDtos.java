package com.geneinvoice.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class DashboardDtos {

    public enum Coverage { ALL, BOOK, OWN }

    public record MonthPoint(String month, BigDecimal amount, long count) {}

    public record MonthlySeries(Coverage coverage, List<MonthPoint> months) {}

    public record AgeBucket(String label, Integer fromDays, Integer toDays,
                            BigDecimal amount, long count,
                            LocalDate dueDateFrom, LocalDate dueDateTo) {}

    public record OutstandingByAge(Coverage coverage, List<AgeBucket> buckets) {}

    public record OutstandingCustomer(Long customerId, String customerName, BigDecimal outstanding,
                                      long openInvoices, Instant oldestInvoiceDate) {}

    public record TopOutstanding(Coverage coverage, List<OutstandingCustomer> customers) {}

    public record PayingCustomer(Long customerId, String customerName, BigDecimal collected,
                                 long payments, Instant lastPaidAt) {}

    public record TopPaying(Coverage coverage, List<PayingCustomer> customers) {}
}
