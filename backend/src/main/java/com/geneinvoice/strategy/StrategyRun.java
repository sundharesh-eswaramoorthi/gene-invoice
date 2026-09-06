package com.geneinvoice.strategy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Durable identity and recovery state of one strategy execution run. A SCHEDULED run is
 * uniquely identified by (strategy_id, business_date); MANUAL runs carry no business date,
 * so the unique constraint never admits two scheduled runs of the same strategy on the same
 * business date while allowing any number of manual runs.
 */
@Entity
@Table(name = "strategy_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_strategy_run_day", columnNames = {"strategy_id", "business_date"})
}, indexes = {
        @Index(name = "idx_strategy_run_due", columnList = "status,next_attempt_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    /** Denormalized so failure alerts need no lazy association outside a transaction. */
    @Column(name = "strategy_title", nullable = false, length = 150)
    private String strategyTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 12)
    private RunTrigger trigger;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    /** DELIVERY when recipient plans were created, ZERO_MATCH when no new group remained. */
    @Column(length = 20)
    private String outcome;

    /** Number of attempts already made (1 after the initial attempt failed once). */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
