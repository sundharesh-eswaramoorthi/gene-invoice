package com.geneinvoice.history;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;

/**
 * The write-side clock, held still, so a test can build a real multi-version timeline without
 * sleeping — and so "two changes inside the same microsecond" is a thing a test can actually
 * arrange rather than hope for (B3).
 *
 * <p>The {@code RecordingMailTransport.Config} idiom exactly: a {@code @TestConfiguration}
 * exposing a {@code @Primary} subclass, imported by the one test class that needs it. Unfrozen it
 * IS the wall clock, so importing it changes nothing until a test freezes it.
 */
public class FixedHistoryClock extends HistoryClock {

    private volatile Instant fixed;

    /** From here on every mirror row is dated at this instant until the clock is moved. */
    public void freezeAt(Instant at) {
        this.fixed = at;
    }

    public void advance(Duration by) {
        Instant now = this.fixed;
        if (now == null) throw new IllegalStateException("The clock is not frozen");
        this.fixed = now.plus(by);
    }

    /** Back to the wall clock, which is what every other test in the run must see. */
    public void release() {
        this.fixed = null;
    }

    public Instant frozenAt() {
        return fixed;
    }

    @Override
    public Instant now() {
        Instant at = this.fixed;
        return at == null ? super.now() : at;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FixedHistoryClock fixedHistoryClock() {
            return new FixedHistoryClock();
        }
    }
}
