package com.geneinvoice.automation;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One FACT: this record changed, at this moment, in this way (A5).
 *
 * <p>The outbox row is written inside the user's own transaction, by
 * {@link ChangeFeed.Publication#beforeCommit}, so the change and the fact about the change commit
 * together or neither does. That is the whole durability claim: if the consumer is down, or
 * broken, or was never started, the row is still here and the sweeper finds it.
 *
 * <p>It is a FACT and not a plan. Nothing here names a rule, because the rules that will be
 * applied are read when the event is fanned out and not when it is published — a rule edited
 * between the save and the sweep is applied as it is NOW, and nothing was promised in between
 * (A5).
 *
 * <p>DELIBERATELY NO UNIQUE KEY. Two transactions that change the same record really are two
 * events; a rule that must not act twice is protected by the event claim and by
 * {@code uk_step_occasion} downstream, not by pretending the second change did not happen (A5).
 */
@Entity
@Table(name = "automation_events",
        indexes = @Index(name = "idx_event_due", columnList = "status,next_attempt_at,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationEvent {

    /** What a fan-out failure is allowed to write down. varchar and never @Lob (A5). */
    public static final int ERROR_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    // A raw id with no association and no foreign key, the AuditLog.entityId / Dispute.customerId
    // house convention: an event outlives the record it is about, and a row that is gone must not
    // take the trail of what happened to it with it (A5).
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Change change;

    // @ColumnDefault so a column added to a by-then-populated table cannot repeat the D-01
    // failure, and @Builder.Default so every insert path sets it anyway (A5).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @ColumnDefault("'NEW'")
    @Builder.Default
    private EventStatus status = EventStatus.NEW;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int attempts = 0;

    // Null means due now. A failed fan-out sets it forward, which is what keeps one poisoned
    // event from being retried in a tight loop ahead of every other one (A5).
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;

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
