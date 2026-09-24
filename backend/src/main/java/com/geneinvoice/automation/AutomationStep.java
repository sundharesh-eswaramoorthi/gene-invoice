package com.geneinvoice.automation;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * THE UNIT OF WORK: one action, of one rule, against one record, on one occasion (A5).
 *
 * <p>{@code uk_step_occasion} IS the idempotency key. An event redelivered, a schedule claimed
 * twice, a double-clicked "run now" — each produces the same {@code occasion} and the second
 * INSERT is refused by the DATABASE. That is deliberate and it is the whole no-double-action
 * guarantee: a check-then-act would have a window between the check and the act, and this one has
 * none (A5).
 *
 * <p>{@code claim_token} IS THE FENCE. A worker claims a step by writing its own token; it may
 * only settle a step that still carries that token. A step reclaimed by the sweeper after a stall
 * therefore cannot be settled by the worker that lost it, and — because the settle and the domain
 * write are ONE transaction — the loser's Task or Email rolls back with it. Committing the domain
 * write in an inner transaction "for tidiness" would produce two tasks (A5).
 *
 * <p>{@code customer_id} is NOT NULL and it is the region axis: AutomationStep is VIA_CUSTOMER_ID,
 * so the run history is region-scoped by TableQueryExecutor with no scope argument anybody could
 * forget. Every subject a rule can have — a customer, an invoice, a payment — has an account
 * behind it, so there is always an answer (A5, B1).
 *
 * <p>{@code rule_name} and {@code rule_version} are SNAPSHOTS and {@code rule_id} is a raw Long
 * with no foreign key, so history survives a rename and a soft delete (A5).
 */
@Entity
@Table(name = "automation_steps",
        uniqueConstraints = @UniqueConstraint(name = "uk_step_occasion",
                columnNames = {"occasion", "rule_id", "action_index", "subject_type", "subject_id"}),
        indexes = {
                @Index(name = "idx_step_due", columnList = "status,next_attempt_at,id"),
                @Index(name = "idx_step_group", columnList = "occasion,rule_id,subject_id,action_index"),
                @Index(name = "idx_step_rule", columnList = "rule_id,id"),
                @Index(name = "idx_step_subject", columnList = "subject_type,subject_id,id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationStep {

    /** {@code E<eventId>} | {@code S<slot>} | {@code M<requestId>}, the idempotency key's head. */
    public static final int OCCASION_MAX = 80;

    /** The fence's own width: a UUID without its dashes fits with room to spare (A5). */
    public static final int CLAIM_TOKEN_MAX = 40;

    /** A CSV of placeholder and role tokens that had nothing behind them, EmailText.fit'd (A4). */
    public static final int UNRESOLVED_MAX = 500;

    /** The skip reason or the error, and varchar rather than @Lob for the proven reason (A5). */
    public static final int RESULT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = OCCASION_MAX)
    private String occasion;

    // A raw id with no association and no foreign key, the Dispute.customerId house convention: a
    // step outlives the rule it came from, and a soft-deleted rule must not take its history with
    // it (A5).
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_name", nullable = false, length = FieldLimits.RULE_NAME)
    private String ruleName;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "action_index", nullable = false)
    private int actionIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_kind", nullable = false, length = 20)
    private ActionKind actionKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    // The region axis. NOT NULL, so a step in no account cannot be a step in no branch that a
    // region-scoped read would silently skip rather than refuse (A5, B1).
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Null for the event path: only a schedule and a manual run have a run to belong to (A5). */
    @Column(name = "run_id")
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StepSource source;

    // @ColumnDefault so a column added to a by-then-populated table cannot repeat the D-01
    // failure, and @Builder.Default so every insert path sets it anyway (A5).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @ColumnDefault("'QUEUED'")
    @Builder.Default
    private StepStatus status = StepStatus.QUEUED;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int attempts = 0;

    /** Null means due now; a failed attempt sets it forward so one bad step cannot starve the queue. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "claim_token", length = CLAIM_TOKEN_MAX)
    private String claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "produced_type", length = 20)
    private ProducedType producedType;

    @Column(name = "produced_id")
    private Long producedId;

    @Column(length = UNRESOLVED_MAX)
    private String unresolved;

    @Column(length = RESULT_MAX)
    private String result;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
