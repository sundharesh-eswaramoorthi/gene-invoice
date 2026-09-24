package com.geneinvoice.history;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The WRITE-side time seam of the mirror, and one of exactly three named time seams in this
 * programme — the other two being the automation dispatcher's package-private overloads and
 * {@code common/asof/AsOfContext} on the read side (B3).
 *
 * <p>It is a bean and not a {@code java.time.Clock} because the blueprint's rule is that a time
 * seam has a NAME a reviewer can grep for. It exists so a test can build a real multi-version
 * timeline — two versions of one invoice, an hour apart, without sleeping — and it is the only
 * test-only seam on the write path, which is worth naming out loud rather than hiding behind a
 * "for testing" comment.
 *
 * <p>Overridden in tests by a {@code @TestConfiguration} exposing a {@code @Primary} subclass,
 * exactly the way {@code RecordingMailTransport.Config} does it (B3).
 */
@Component
public class HistoryClock {

    /**
     * ALWAYS the wall clock, and never {@code AsOfContext}: as-of is a READ mode, and a mirror row
     * dated from a replayed past would make the past disagree with itself. B3-WRITER's drain()
     * throws outright when a context is open, which is the third guard on the same rule (B3).
     */
    public Instant now() {
        return Instant.now();
    }
}
