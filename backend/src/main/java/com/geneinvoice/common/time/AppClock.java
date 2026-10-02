package com.geneinvoice.common.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The business clock for code Spring cannot inject: entity callbacks and static helpers. Set by
 * {@link AppClockBridge} to the primary {@link Clock} bean; it starts on the system clock so a callback
 * that fires before the bridge exists never sees null.
 */
public final class AppClock {

    private static volatile Clock clock = Clock.systemUTC();

    private AppClock() {}

    public static Clock get() {
        return clock;
    }

    public static Instant now() {
        return clock.instant();
    }

    public static void set(Clock c) {
        clock = Objects.requireNonNull(c, "clock");
    }
}
