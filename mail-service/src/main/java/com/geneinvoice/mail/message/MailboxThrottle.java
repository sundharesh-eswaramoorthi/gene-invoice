package com.geneinvoice.mail.message;

import com.geneinvoice.mail.config.MailProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class MailboxThrottle {

    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Map<Long, Long> lastStart = new ConcurrentHashMap<>();

    @Autowired
    public MailboxThrottle(MailProperties properties) {
        this(properties.getSend().getPerMailboxIntervalMs(), System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    MailboxThrottle(long intervalMs, LongSupplier nanoTime, Sleeper sleeper) {
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public void acquire(long connectionId) throws InterruptedException {
        long now = nanoTime.getAsLong();
        long slot = lastStart.merge(connectionId, now,
                (previous, current) -> current - previous >= intervalNanos ? current : previous + intervalNanos);
        long wait = slot - now;
        if (wait > 0) sleeper.sleep(wait);
    }
}
