package com.geneinvoice.strategy;

import com.geneinvoice.GeneInvoiceApplication;
import com.geneinvoice.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of the seams the criteria depend on but no compiler can check: the admin-only guard
 * on every strategy operation (AQ5), the 05:00 default run time carried by the deployment
 * configuration file (AC7), startup rejection of an invalid time or timezone (FR6), and the
 * scheduler being a reconciliation tick rather than a once-a-day in-memory cron (AQ3).
 */
class StrategyWiringTest {

    @Test
    void everyStrategyEndpointIsBehindTheExistingRoleAdminAuthority() {
        PreAuthorize guard = StrategyController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(guard, "the strategy controller must carry a class-level guard");
        assertEquals("hasRole('ADMIN')", guard.value(),
                "strategy management and manual runs require the existing ROLE_ADMIN authority");
        assertEquals("/api/notification-strategies",
                StrategyController.class.getAnnotation(
                        org.springframework.web.bind.annotation.RequestMapping.class).value()[0]);
    }

    @Test
    void clockDefaultsToFiveAmAndBindsTheBusinessTimezone() {
        StrategyClock clock = new StrategyClock(
                Clock.fixed(java.time.Instant.parse("2024-06-01T05:00:00Z"), ZoneOffset.UTC),
                "05:00", "Asia/Kolkata");
        assertEquals(LocalTime.of(5, 0), clock.runTime(),
                "before reconfiguration the global run time is 5:00 AM");
        assertEquals(ZoneId.of("Asia/Kolkata"), clock.zoneId());
    }

    @Test
    void invalidRunTimeOrTimezoneIsRejectedAtBindingTime() {
        Clock clock = Clock.systemUTC();
        assertThrows(DateTimeException.class,
                () -> new StrategyClock(clock, "25:99", "UTC"));
        assertThrows(DateTimeException.class,
                () -> new StrategyClock(clock, "05:00", "Not/AZone"));
    }

    @Test
    void deploymentConfigFileCarriesTheFiveAmDefault() throws Exception {
        // Read the same application.yml the running system binds; the default must be the
        // same literal, not merely the same shape.
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(yml.contains("run-time: ${STRATEGY_RUN_TIME:05:00}"),
                "the global run time defaults to 05:00 and is deployment-configurable");
        assertTrue(yml.contains("timezone: ${STRATEGY_BUSINESS_TIMEZONE:UTC}"),
                "the business timezone is globally configurable");
    }

    @Test
    void schedulerIsAPeriodicReconciliationTickNotAnInMemoryDailyCron() throws Exception {
        assertTrue(GeneInvoiceApplication.class.isAnnotationPresent(EnableScheduling.class));
        Method tick = StrategySchedulingConfig.class.getMethod("reconcileDueStrategyWork");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "strategy work is woken by @Scheduled");
        assertEquals(60_000L, scheduled.fixedDelay(),
                "a periodic tick reconciles persisted due work instead of relying on one cron firing");
        assertEquals(0, scheduled.cron().length() > 0 ? 1 : 0,
                "no in-memory cron decides daily admission");
    }

    @Test
    void notificationServiceKeepsTheFiveArgumentDisputePathAndGainsAStrategyOverload()
            throws Exception {
        // The additive bridge contract the dispute producers and strategy engine both rely on.
        assertNotNull(NotificationService.class.getMethod(
                "notify", Long.class, String.class, String.class, String.class, String.class),
                "the existing five-argument notify is preserved for disputes");
        assertNotNull(NotificationService.class.getMethod(
                "notifyAdmins", String.class, String.class, String.class, String.class));
        assertNotNull(NotificationService.class.getMethod(
                "notifyStrategy", Long.class, String.class, String.class, String.class,
                String.class, List.class),
                "the strategy-specific per-recipient insertion overload exists");
    }
}
