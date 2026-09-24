package com.geneinvoice.dashboard;

import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The five figures. Each takes an optional ?region=3&region=7 because a dashboard has no
 * TableQuery to carry a regionId filter the way every list does; it can only ever NARROW what the
 * caller's own grants already allow, and each answer SAYS which branches it counted (B1).
 *
 * <p>ALL FIVE ALSO TAKE ?asOf, AND NOT ONE LINE OF THIS FILE SAYS SO. The parameter is admitted by
 * AsOfEndpoints.AS_OF_CAPABLE and turned into a thread state by AsOfInterceptor before any handler
 * below runs, so the five methods keep their exact signatures and the service branches on the
 * thread rather than on an argument. {@link #today()} is the payoff of the one clock seam S4 cut:
 * it already returned {@code InvoiceDates.today()}, which follows the reader into the past, so the
 * month window, the ageing buckets and the dueDateFrom/dueDateTo each bucket reports are all
 * as-of with no edit here at all (B3).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping("/billed-by-month")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.MonthlySeries billedByMonth(@RequestParam(defaultValue = "12") int months,
                                                     @RequestParam(required = false) List<Long> region) {
        return service.billedByMonth(months, today(), region);
    }

    @GetMapping("/outstanding-by-age")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.OutstandingByAge outstandingByAge(@RequestParam(required = false) List<Long> region) {
        return service.outstandingByAge(today(), region);
    }

    @GetMapping("/top-outstanding-customers")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.TopOutstanding topOutstanding(@RequestParam(defaultValue = "5") int limit,
                                                       @RequestParam(required = false) List<Long> region) {
        return service.topOutstanding(limit, region);
    }

    @GetMapping("/collected-by-month")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public DashboardDtos.MonthlySeries collectedByMonth(@RequestParam(defaultValue = "12") int months,
                                                        @RequestParam(required = false) List<Long> region) {
        return service.collectedByMonth(months, today(), region);
    }

    @GetMapping("/top-paying-customers")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public DashboardDtos.TopPaying topPaying(@RequestParam(defaultValue = "12") int months,
                                            @RequestParam(defaultValue = "5") int limit,
                                            @RequestParam(required = false) List<Long> region) {
        return service.topPaying(months, limit, today(), region);
    }

    /**
     * UNCHANGED BY B3, WHICH IS THE WHOLE POINT OF IT. InvoiceDates.today() reads AsOfContext, so
     * under ?asOf this IS the date asked for and the three figures that take a date age against it
     * without knowing the feature exists. todayForWrite() is the half that never moves, and no
     * write is reachable from here (B3).
     */
    private static LocalDate today() {
        return InvoiceDates.today();
    }
}
