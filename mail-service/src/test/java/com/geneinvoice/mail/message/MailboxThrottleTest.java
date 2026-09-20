package com.geneinvoice.mail.message;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** One send per mailbox per interval, measured from when the previous one started. */
class MailboxThrottleTest {

    private static final long MS = 1_000_000;

    private final AtomicLong now = new AtomicLong(1_000 * MS);
    private final List<Long> slept = new ArrayList<>();
    /** Sleeping moves the fake time on, as a real sleep would. */
    private final MailboxThrottle throttle = new MailboxThrottle(500, now::get, nanos -> {
        slept.add(nanos / MS);
        now.addAndGet(nanos);
    });

    @Test
    void sendsFromOneMailboxAreSpacedAndOtherMailboxesAreNotHeldUp() throws Exception {
        throttle.acquire(1);
        throttle.acquire(2);
        assertThat(slept).isEmpty();

        throttle.acquire(1);
        assertThat(slept).containsExactly(500L);

        // 200 ms later the next slot is 300 ms away.
        now.addAndGet(200 * MS);
        throttle.acquire(1);
        assertThat(slept).containsExactly(500L, 300L);

        // After a quiet spell, no wait.
        now.addAndGet(5_000 * MS);
        throttle.acquire(1);
        throttle.acquire(2);
        assertThat(slept).containsExactly(500L, 300L);
    }

    @Test
    void callersArrivingTogetherQueueUpInOrder() throws Exception {
        List<Long> waits = new CopyOnWriteArrayList<>();
        AtomicLong fixed = new AtomicLong(0);
        // Time stands still: each caller reserves the next slot and would wait that long.
        MailboxThrottle standingStill = new MailboxThrottle(500, fixed::get, nanos -> waits.add(nanos / MS));

        for (int i = 0; i < 4; i++) standingStill.acquire(7);

        assertThat(waits).containsExactly(500L, 1000L, 1500L);
    }

    @Test
    void onTheRealClockFourSendsFromOneMailboxTakeThreeIntervals() throws Exception {
        MailboxThrottle real = new MailboxThrottle(40, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
        List<Long> started = new CopyOnWriteArrayList<>();
        long begin = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                all.add(pool.submit(() -> {
                    real.acquire(3);
                    started.add(System.nanoTime());
                    return null;
                }));
            }
            for (Future<?> f : all) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // The last of the four slots starts three intervals after the first, whoever got which.
        long last = started.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(Duration.ofNanos(last - begin)).isGreaterThanOrEqualTo(Duration.ofMillis(115));

        // Another mailbox is not held up.
        long other = System.nanoTime();
        real.acquire(4);
        assertThat(Duration.ofNanos(System.nanoTime() - other)).isLessThan(Duration.ofMillis(30));
    }

    @Test
    void noIntervalMeansNoWait() throws Exception {
        MailboxThrottle none = new MailboxThrottle(0, now::get, nanos -> slept.add(nanos));
        none.acquire(1);
        none.acquire(1);
        assertThat(slept).isEmpty();
    }
}
