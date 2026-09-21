package com.geneinvoice.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MovableClock extends Clock {

    private volatile Instant fixed;
    private volatile Duration offset = Duration.ZERO;

    public MovableClock() {}

    public MovableClock(Instant fixed) {
        this.fixed = fixed;
    }

    public void set(Instant instant) {
        fixed = instant;
    }

    public void advance(Duration by) {
        if (fixed != null) fixed = fixed.plus(by);
        else offset = offset.plus(by);
    }

    public void reset() {
        fixed = null;
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant at = fixed;
        return at != null ? at : Instant.now().plus(offset);
    }
}
