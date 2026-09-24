package com.geneinvoice.dashboard;

import com.geneinvoice.common.asof.AsOfInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class DashboardDtos {

    // Gains no constant for regions: this is about the BOOK — whose records am I looking at — and
    // the region a record lives in is an orthogonal axis that answers a different question, so it
    // is reported beside this one rather than folded into it (B1).
    public enum Coverage { ALL, BOOK, OWN }

    /** One branch, named the way the region map names it, so a figure can say where it looked. */
    public record RegionRef(Long id, String code, String name) {}

    /**
     * Which branches a figure counted, said out loud. A list says it with
     * PageResponse.lockedFilters; a dashboard has no TableQuery and no chips, so it says it here.
     * allRegions is the wildcard holder's answer and the customer login's (neither is narrowed by
     * a branch), and allRegions=false with an EMPTY list is the honest "you can see no region at
     * all" that explains why every figure on the page is zero (B1).
     */
    public record RegionCoverage(boolean allRegions, List<RegionRef> regions) {}

    public record MonthPoint(String month, BigDecimal amount, long count) {}

    /**
     * WHICH DATE THIS FIGURE WAS ASKED AS OF, and null on every live read — the third component of
     * all four payloads, after Coverage and RegionCoverage and before the payload itself, which is
     * the trailing order the blueprint fixes once so three features can append without colliding.
     *
     * <p>A dashboard has no PageResponse envelope and no lockedFilters chip to say it with, so a
     * number from the past says it here or nowhere: a reader shown 100.00 has to be able to tell
     * an exact reconstruction from a seeded approximation without reading the release notes.
     * Coverage {ALL, BOOK, OWN} gains no constant — as-of does not change how WIDE a figure is,
     * only WHEN it is (B3).
     */
    public record MonthlySeries(Coverage coverage, RegionCoverage regionCoverage, AsOfInfo asOf,
                                List<MonthPoint> months) {}

    public record AgeBucket(String label, Integer fromDays, Integer toDays,
                            BigDecimal amount, long count,
                            LocalDate dueDateFrom, LocalDate dueDateTo) {}

    public record OutstandingByAge(Coverage coverage, RegionCoverage regionCoverage, AsOfInfo asOf,
                                   List<AgeBucket> buckets) {}

    public record OutstandingCustomer(Long customerId, String customerName, BigDecimal outstanding,
                                      long openInvoices, Instant oldestInvoiceDate) {}

    public record TopOutstanding(Coverage coverage, RegionCoverage regionCoverage, AsOfInfo asOf,
                                 List<OutstandingCustomer> customers) {}

    public record PayingCustomer(Long customerId, String customerName, BigDecimal collected,
                                 long payments, Instant lastPaidAt) {}

    public record TopPaying(Coverage coverage, RegionCoverage regionCoverage, AsOfInfo asOf,
                            List<PayingCustomer> customers) {}
}
