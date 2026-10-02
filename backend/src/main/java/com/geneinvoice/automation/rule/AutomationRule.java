package com.geneinvoice.automation.rule;

import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.time.AppClock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A rule (§3.6): when a record of one kind is created or updated, on a schedule, or when run by hand, act on the
 * records its condition matches, as its owner (P3). Only user edits go through the entity; what the engine keeps
 * about its runs is written by JPQL, so it never bumps the version a user is editing against.
 */
@Entity
@Table(name = "automation_rules", indexes = {
        @Index(name = "idx_automation_rule_trigger", columnList = "entity_type,trigger_kind,enabled"),
        @Index(name = "idx_automation_rule_next_run", columnList = "next_run_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRule {

    public static final int NAME_MAX = 120;
    public static final int DESCRIPTION_MAX = 500;
    public static final String CATCH_UP_ONE = "ONE";
    public static final String CATCH_UP_ALL = "ALL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique among rules not deleted, whatever its case; checked by the service. */
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    /** Who the rule runs as. No foreign key: a rule outlives the question of whether its owner can still act. */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private boolean enabled;

    /** Entity events that happened before this are not the rule's to act on (turned on, or resumed without replay). */
    @Column(name = "active_since")
    private Instant activeSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspended_reason", length = 30)
    private SuspendReason suspendedReason;

    @Column(name = "suspended_detail", length = 500)
    private String suspendedDetail;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 30)
    private TriggerKind triggerKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private TriggerEntity entityType;

    /** HH:mm in {@link #scheduleZone}, for a schedule. */
    @Column(name = "schedule_time", length = 5)
    private String scheduleTime;

    /** A {@link java.time.DayOfWeek}, for a weekly schedule. */
    @Column(name = "schedule_day", length = 9)
    private String scheduleDay;

    @Column(name = "schedule_zone", nullable = false, length = 40)
    private String scheduleZone;

    /** ONE or ALL: after missed slots, one catch-up run or one per slot. */
    @Column(name = "catch_up", nullable = false, length = 4)
    private String catchUp;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_scheduled_for")
    private Instant lastScheduledFor;

    /** The condition as {@code ConditionJson} writes it; null matches every record. */
    @Lob
    @Column(name = "condition_json", columnDefinition = "TEXT")
    private String conditionJson;

    @Lob
    @Column(name = "actions_json", columnDefinition = "TEXT", nullable = false)
    private String actionsJson;

    @Column(name = "max_records_per_run", nullable = false)
    private int maxRecordsPerRun;

    @Column(name = "last_run_id")
    private Long lastRunId;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private RunStatus lastRunStatus;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set when the rule is deleted: its revisions, runs and audit trail stay. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private Long version;

    /**
     * Where the rule may act (PC-8). Empty means every region where its owner holds what the rule needs; otherwise
     * only these, and only while the owner still does.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "automation_rule_regions", joinColumns = @JoinColumn(name = "rule_id"),
            indexes = @Index(name = "idx_rule_region", columnList = "region_id"))
    @Column(name = "region_id", nullable = false)
    @BatchSize(size = 50)
    @Builder.Default
    private Set<Long> regionIds = new HashSet<>();

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isSuspended() {
        return suspendedReason != null;
    }

    @PrePersist
    void onCreate() {
        Instant now = AppClock.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppClock.now();
    }
}
