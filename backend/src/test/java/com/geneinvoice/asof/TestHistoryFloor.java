package com.geneinvoice.asof;

import com.geneinvoice.common.asof.AsOfFloor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * The history floor, movable, so a test can ask about a date the mirror really does cover (B3).
 *
 * <p>WHY THIS EXISTS AND WHY IT IS NOT A CONVENIENCE. The real floor is installed once by
 * HistorySeedUpgrade at the instant the application first boots, and HistoryFloorService caches a
 * non-null answer for the life of the context. In a test run that instant is TODAY — so without
 * this bean every date any test can ask about is before the floor, every as-of response comes back
 * {@code origin: SEEDED, exact: false}, and the RECONSTRUCTED branch — the normal one, the one
 * every real installation serves after its first week — would never execute over HTTP at all.
 *
 * <p>Default: 2000-01-01, far below any date a test asks about, so the answers are the ordinary
 * reconstructed ones. {@link #at} moves it forward, which is how the PRE-FLOOR branch gets its own
 * end-to-end coverage instead of being asserted on AsOfDates in isolation.
 *
 * <p>The {@code RecordingMailTransport.Config} / {@code FixedHistoryClock.Config} idiom exactly: a
 * {@code @TestConfiguration} exposing a {@code @Primary} bean, imported by the classes that need
 * it. AsOfInterceptor and AsOfController both hold {@link AsOfFloor} through an ObjectProvider, so
 * @Primary is all it takes and neither of them is aware of this.
 */
public class TestHistoryFloor implements AsOfFloor {

    /** Far enough back that every date these tests ask about is ABOVE the floor. */
    public static final LocalDate DEFAULT = LocalDate.of(2000, 1, 1);

    private volatile LocalDate floor = DEFAULT;

    public void at(LocalDate day) {
        this.floor = day;
    }

    public void reset() {
        this.floor = DEFAULT;
    }

    @Override
    public LocalDate floorOrNull() {
        return floor;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        TestHistoryFloor testHistoryFloor() {
            return new TestHistoryFloor();
        }
    }
}
