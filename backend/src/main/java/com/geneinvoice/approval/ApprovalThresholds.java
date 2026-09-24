package com.geneinvoice.approval;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Where "how much is too much here?" is answered, once. Every gate site asks this and nothing
 * else, so a region's limit is one row and a fallback rather than a rule copied into twelve
 * mutators (B2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalThresholds {

    private final ApprovalThresholdRepository repository;
    private final ApprovalProperties properties;

    /** Disabled is not zero: {@code enabled=false} holds nothing at all, whatever the amount. */
    public record Limit(boolean enabled, BigDecimal amount) {}

    /** The region's own row, or the deployment default; a deployment that configures no default
     *  holds nothing, so switching maker-checker on is an operational act and not a side effect
     *  of a release (B2). */
    public Limit forRegion(Long regionId) {
        return repository.findByRegionId(regionId)
                .map(t -> new Limit(t.isEnabled(), t.getAmount()))
                .orElseGet(() -> properties.defaultThreshold() == null
                        ? new Limit(false, BigDecimal.ZERO)
                        : new Limit(true, properties.defaultThreshold()));
    }

    /**
     * Says out loud, once at startup, that the feature is installed and dormant. A silent dormant
     * gate is indistinguishable from a broken one, and the difference matters the first time
     * somebody asks why a large payment went straight through (B2).
     */
    @PostConstruct
    void announceIfDormant() {
        try {
            if (properties.defaultThreshold() == null && repository.count() == 0) {
                log.warn("No approval threshold is configured anywhere and APPROVAL_DEFAULT_THRESHOLD"
                        + " is unset, so maker-checker is configured to hold nothing. Set a default"
                        + " or give a region a limit to turn it on (B2)");
            }
        } catch (RuntimeException e) {
            // A startup notice is never a reason to refuse to start, the SchemaSupport rule (B2).
            log.warn("Could not check whether maker-checker is configured to hold anything: {}",
                    e.getMessage());
        }
    }
}
