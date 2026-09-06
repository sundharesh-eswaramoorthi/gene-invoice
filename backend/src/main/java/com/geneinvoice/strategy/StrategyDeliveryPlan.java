package com.geneinvoice.strategy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One frozen per-recipient delivery envelope of a strategy run. Each recipient of a
 * strategy/customer group gets an independent plan, so one recipient's delivery failure stays
 * retryable even after another recipient's success consumes the strategy–invoice pairs for
 * later runs.
 */
@Entity
@Table(name = "strategy_delivery_plans", indexes = {
        @Index(name = "idx_plan_run", columnList = "run_id"),
        @Index(name = "idx_plan_due", columnList = "state,next_attempt_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyDeliveryPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "strategy_title", nullable = false, length = 150)
    private String strategyTitle;

    /** Null for zero-match plans, which belong to no customer group. */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "notif_type", nullable = false, length = 40)
    private String notifType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String message;

    @Column(length = 300)
    private String link;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PlanState state;

    /** Number of attempts already made (1 after the initial attempt failed once). */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StrategyDeliveryPlanItem> items = new ArrayList<>();

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
