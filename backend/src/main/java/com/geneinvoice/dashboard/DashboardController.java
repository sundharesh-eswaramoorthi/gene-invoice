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

/**
 * Read-only figures for the dashboard. Each endpoint needs the privilege of the list it summarises,
 * and customer logins get no customer rankings.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping("/billed-by-month")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.MonthlySeries billedByMonth(@RequestParam(defaultValue = "12") int months) {
        return service.billedByMonth(months, today());
    }

    @GetMapping("/outstanding-by-age")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.OutstandingByAge outstandingByAge() {
        return service.outstandingByAge(today());
    }

    @GetMapping("/top-outstanding-customers")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
    public DashboardDtos.TopOutstanding topOutstanding(@RequestParam(defaultValue = "5") int limit) {
        return service.topOutstanding(limit);
    }

    @GetMapping("/collected-by-month")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public DashboardDtos.MonthlySeries collectedByMonth(@RequestParam(defaultValue = "12") int months) {
        return service.collectedByMonth(months, today());
    }

    @GetMapping("/top-paying-customers")
    @PreAuthorize("hasAuthority('" + Privileges.PAYMENT_VIEW + "')")
    public DashboardDtos.TopPaying topPaying(@RequestParam(defaultValue = "12") int months,
                                            @RequestParam(defaultValue = "5") int limit) {
        return service.topPaying(months, limit, today());
    }

    /** The one definition of today the app has, so the ageing chart and the invoice list's
     * overdue filter can never disagree about which invoices are late. */
    private static LocalDate today() {
        return InvoiceDates.today();
    }
}
