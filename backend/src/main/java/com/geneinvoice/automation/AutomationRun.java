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
import java.time.LocalDate;

/**
 * One firing of one rule over MANY records: a schedule's slot, or somebody pressing "run now".
 *
 * <p>There is deliberately no run row on the event path. An event is about ONE record and its
 * steps are planned directly; wrapping it in a run would be a row per save whose only content is
 * "one record matched" (A5).
 *
 * <p>{@code cursor_subject_id} is a KEYSET cursor, advanced and committed with each page of
 * steps, which is what makes a fan-out over 50,000 records resumable: a crash halfway through
 * resumes from the last committed page and never plans a step twice. {@code truncated} is SHOWN
 * on the run rather than swallowed, because a rule that stopped at the cap did not finish and the
 * person reading the history has to know (A5).
 *
 * <p>{@code uk_run_occasion} is the barrier the overrun check is not: {@code existsByRuleIdAndStatusIn}
 * is racy and does not need not to be, because two instances claiming the same slot both try to
 * insert the same (rule_id, occasion) and exactly one wins (A5).
 */
@Entity
@Table(name = "automation_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_run_occasion",
                columnNames = {"rule_id", "occasion"}),
        indexes = @Index(name = "idx_run_rule", columnList = "rule_id,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRun {

    /** What a failed run is allowed to write down. varchar and never @Lob (A5). */
    public static final int ERROR_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A raw id with no association and no foreign key, so a soft-deleted rule's runs stay
    // readable — the same reason a step keeps a raw rule_id (A5).
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_name", nullable = false, length = FieldLimits.RULE_NAME)
    private String ruleName;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(nullable = false, length = AutomationStep.OCCASION_MAX)
    private String occasion;

    /** SCHEDULE or MANUAL only; EVENT never has a run (A5). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StepSource source;

    /**
     * The date this run evaluates as of. DATE ARITHMETIC ANYWHERE IN PART A READS THIS, never
     * Instant.now() and never InvoiceDates.today(), so a replayed run produces the same dates it
     * produced the first time (A5, B3).
     */
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ColumnDefault("'FANNING'")
    @Builder.Default
    private RunStatus status = RunStatus.FANNING;

    @Column(name = "cursor_subject_id", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private Long cursorSubjectId = 0L;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int matched = 0;

    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean truncated = false;

    @Column(name = "steps_planned", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int stepsPlanned = 0;

    /** Null for a schedule: nobody asked for it, the clock did (A5). */
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(length = ERROR_MAX)
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

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
        if (this.startedAt == null) this.startedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
