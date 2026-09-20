package com.geneinvoice.mail.message;

import com.geneinvoice.mail.config.MailProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Gmail's per-user rate limit, kept on our side (M7): one mailbox starts a send at most once per
 * {@code mail.send.per-mailbox-interval-ms}, however many workers hold its copies. Each caller
 * reserves the next free slot at once and then waits for it, so callers queue up in order instead of
 * racing. Other mailboxes are not held up. Measured on the monotonic clock, not the wall clock.
 */
@Component
public class MailboxThrottle {

    /** Waits the given nanoseconds; replaced in tests. */
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    /** Per connection id: when the last reserved send may start. */
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

    /** Returns once this mailbox may start its next send. */
    public void acquire(long connectionId) throws InterruptedException {
        long now = nanoTime.getAsLong();
        long slot = lastStart.merge(connectionId, now,
                (previous, current) -> current - previous >= intervalNanos ? current : previous + intervalNanos);
        long wait = slot - now;
        if (wait > 0) sleeper.sleep(wait);
    }
}
