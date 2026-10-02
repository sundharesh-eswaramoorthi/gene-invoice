package com.geneinvoice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The tests' business clock. It follows the system clock until a test sets it, so a test that never
 * touches it sees real time; {@link IntegrationTestBase} resets it before every test.
 */
public final class MovableClock extends Clock {

    public static final MovableClock SHARED = new MovableClock();

    private volatile Instant fixed;

    private MovableClock() {}

    public void set(Instant instant) {
        fixed = instant;
    }

    public void advance(Duration by) {
        fixed = instant().plus(by);
    }

    public void reset() {
        fixed = null;
    }

    @Override
    public Instant instant() {
        Instant f = fixed;
        return f != null ? f : Clock.systemUTC().instant();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MovableClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }
}
