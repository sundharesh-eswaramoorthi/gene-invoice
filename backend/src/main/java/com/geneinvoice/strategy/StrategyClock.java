package com.geneinvoice.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The single business clock for strategy scheduling: one deployment-configured daily run time
 * ({@code app.strategy.run-time}, default 05:00) and one deployment-configured IANA business
 * timezone ({@code app.strategy.timezone}, default UTC). Both are bound and validated at
 * startup — an invalid time or timezone fails the application context, so a deployment change
 * takes effect after restart and a bad value never silently runs.
 */
@Component
public class StrategyClock {

    private final Clock clock;
    private final LocalTime runTime;
    private final ZoneId zoneId;

    public StrategyClock(Clock clock,
                         @Value("${app.strategy.run-time:05:00}") String runTime,
                         @Value("${app.strategy.timezone:UTC}") String timezone) {
        this.clock = clock;
        this.runTime = LocalTime.parse(runTime);
        this.zoneId = ZoneId.of(timezone);
    }

    public LocalTime runTime() {
        return runTime;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZonedDateTime nowZoned() {
        return clock.instant().atZone(zoneId);
    }

    public LocalDate currentBusinessDate() {
        return nowZoned().toLocalDate();
    }

    /**
     * The business date whose daily admission is due now: today once the configured run time
     * has passed, otherwise yesterday — so a window missed while the app was down is
     * reconciled at the next tick until the following due time arrives.
     */
    public LocalDate dueBusinessDate() {
        ZonedDateTime now = nowZoned();
        LocalDate today = now.toLocalDate();
        return now.toLocalTime().isBefore(runTime) ? today.minusDays(1) : today;
    }
}
