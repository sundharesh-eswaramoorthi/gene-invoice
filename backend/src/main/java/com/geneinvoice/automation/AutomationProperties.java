package com.geneinvoice.automation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The deployment's own answers about the engine. The ApprovalProperties shape: a @Component bound
 * to a prefix, with short accessors beside Lombok's getters so call sites read as facts rather
 * than as bean plumbing (A5).
 *
 * <p>{@code async: false} plus a long sweep interval is the ROLLBACK LEVER for this whole
 * feature: the engine stops acting without losing a single event, because the event rows were
 * committed inside the user's own save (A5).
 */
@Component
@ConfigurationProperties(prefix = "app.automation")
@Getter
@Setter
public class AutomationProperties {

    private long sweepIntervalMs = 60000;

    /** false hands a run over inline, which is what the tests want and what a rollback wants. */
    private boolean async = true;

    /** A runaway rule stops itself rather than the database, and the run SAYS it was truncated. */
    private int maxStepsPerRun = 50000;

    private Duration keepEvents = Duration.ofDays(30);

    private Duration keepSteps = Duration.ofDays(180);

    public long sweepIntervalMs() {
        return sweepIntervalMs;
    }

    public boolean async() {
        return async;
    }

    public int maxStepsPerRun() {
        return maxStepsPerRun;
    }

    public Duration keepEvents() {
        return keepEvents;
    }

    public Duration keepSteps() {
        return keepSteps;
    }
}
