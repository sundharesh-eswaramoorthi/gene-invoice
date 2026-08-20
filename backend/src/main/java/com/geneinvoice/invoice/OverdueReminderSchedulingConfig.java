package com.geneinvoice.invoice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/**
 * Feature-local scheduling configuration for the overdue reminder.
 *
 * <p>Declares the application-wide {@link Clock} bean (system UTC) and carries the
 * daily 02:00 UTC trigger — the only scheduled entry into
 * {@link OverdueReminderSweepService#runOverdueReminderSweep(java.time.Instant)}.
 * Each firing takes a single {@code Instant} from the injected clock and hands it
 * to the sweep. Direct invocation of the sweep with a self-chosen instant remains
 * the sanctioned way to exercise scheduling, failure and catch-up scenarios.
 */
@Configuration
public class OverdueReminderSchedulingConfig {

    private final OverdueReminderSweepService sweepService;
    private final Clock clock;

    public OverdueReminderSchedulingConfig(OverdueReminderSweepService sweepService, Clock clock) {
        this.sweepService = sweepService;
        this.clock = clock;
    }

    /**
     * The time source for the whole application. Declared as a static {@code @Bean}
     * method so it can be created without instantiating this configuration class
     * and therefore without a self-referencing circular dependency.
     */
    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Daily 02:00 UTC trigger for the overdue reminder sweep: obtains the current
     * instant from the injected clock and invokes the sweep once with it.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void runDailyOverdueReminderSweep() {
        sweepService.runOverdueReminderSweep(clock.instant());
    }
}
