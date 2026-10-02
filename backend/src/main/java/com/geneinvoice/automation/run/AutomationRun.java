package com.geneinvoice.automation.run;

import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.common.Fit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One run of a rule for one outbox event (§3.6): what it matched and what came of it. As of a date, a run is there
 * once it was created, and its status then follows from when it started and finished (§5.10.10).
 */
@Entity
@Table(name = "automation_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_automation_run", columnNames = {"rule_id", "event_id"}),
        indexes = @Index(name = "idx_automation_run_rule", columnList = "rule_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRun {

    public static final int MESSAGE_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "rule_version")
    private Long ruleVersion;

    /** The outbox event's kind: CREATED, UPDATED, CHILDREN, SCHEDULED, MANUAL or REPLAY. */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    @Column(name = "entity_type", length = 20)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(nullable = false)
    private int matched;

    @Column(nullable = false)
    private int done;

    @Column(nullable = false)
    private int skipped;

    @Column(nullable = false)
    private int failed;

    @Column(nullable = false)
    private boolean truncated;

    @Column(length = MESSAGE_MAX)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * The run of a rule for an event, as it starts: QUEUED for one asked for (a schedule slot, "Run now"), RUNNING
     * for one the consumer starts itself.
     */
    public static AutomationRun of(Long ruleId, Long ruleVersion, AutomationEvent ev, RunStatus status,
                                   Long requestedByUserId, Instant now) {
        return AutomationRun.builder()
                .ruleId(ruleId)
                .eventId(ev.getId())
                .ruleVersion(ruleVersion)
                .triggerType(ev.getKind())
                .entityType(ev.getEntityType())
                .entityId(ev.getEntityId())
                .requestedByUserId(requestedByUserId)
                .scheduledFor(ev.getScheduledFor())
                .status(status)
                .createdAt(now)
                .startedAt(status == RunStatus.RUNNING ? now : null)
                .build();
    }

    /**
     * Ends the run; the message is fitted, so a long name in it can never fail the write (SC-20). With no message,
     * one the run was given when it was queued (the slots a catch-up left out) stays.
     */
    public void finish(RunStatus to, String text, Instant now) {
        status = to;
        if (text != null) message = Fit.to(text, MESSAGE_MAX);
        finishedAt = now;
        if (startedAt == null) startedAt = now;
    }
}
