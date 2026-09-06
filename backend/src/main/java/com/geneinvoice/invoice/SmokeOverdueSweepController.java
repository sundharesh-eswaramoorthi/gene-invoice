package com.geneinvoice.invoice;

import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * TEMPORARY TEST SCAFFOLD — NOT PART OF THE REQUIREMENT. Delete before merging.
 *
 * <p>The requirement states the ageing policy must not be reachable from the web
 * layer; this controller deliberately breaks that to allow a manual smoke run, which
 * is why it is gated on the non-production {@code smoke} profile and why it must be
 * removed once the smoke test is done.
 *
 * <p>POST /api/test/overdue-sweep/run[?now=2026-08-21T02:00:00Z]
 */
@RestController
@RequestMapping("/api/test/overdue-sweep")
@Profile("smoke")
@RequiredArgsConstructor
public class SmokeOverdueSweepController {

    private final OverdueReminderSweepService sweepService;
    private final Clock clock;

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public Map<String, Object> run(@RequestParam(required = false) String now) {
        Instant at = (now == null || now.isBlank()) ? clock.instant() : Instant.parse(now);
        sweepService.runOverdueReminderSweep(at);
        return Map.of("status", "ok", "ranAt", at.toString());
    }
}
