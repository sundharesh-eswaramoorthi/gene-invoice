package com.geneinvoice.automation;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One rule: what to watch, which records to care about, and what to do about them (A1, A2, A3).
 *
 * <p>THE AUTHOR IS THE ACTOR. {@code created_by_user_id} is NOT NULL and is who every action this
 * rule ever performs is performed AS — a real, accountable person with real rights in real
 * regions, never null and never a synthetic "system" user. A null maker would make "approved by
 * somebody other than the maker" trivially satisfiable and would void maker-checker outright; a
 * synthetic user has the same defect plus no region membership at all (A1, B2 INTEGRATION).
 *
 * <p>SOFT DELETE, so the run history keeps its subject. {@code automation_steps} snapshots
 * {@code rule_name} and carries a raw {@code rule_id} with no foreign key, so a deleted rule
 * leaves the list and its history still names it (A5).
 *
 * <p>{@code definition_version} is bumped on every save that changes WHAT THE RULE DOES —
 * subjectType, triggerKind, the two schedule columns, conditionJson, actionsJson, cooldownDays or
 * the regions — and NOT on name, description or enabled. A step planned against version 4 refuses
 * to run once the rule is at 5, which is what stops a rule edited mid-flight from performing half
 * of the old definition and half of the new one. {@code enabled} is deliberately outside that
 * set and is read LIVE at act time, because "stop doing this" has to mean now and not "after the
 * steps already planned have run" (A1, A5).
 */
@Entity
@Table(name = "automation_rules", indexes = {
        @Index(name = "idx_rule_arm", columnList = "enabled,deleted_at,subject_type,trigger_kind"),
        @Index(name = "idx_rule_due", columnList = "enabled,deleted_at,next_run_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = FieldLimits.RULE_NAME)
    private String name;

    @Column(length = FieldLimits.RULE_DESCRIPTION)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 30)
    private TriggerKind triggerKind;

    @Column(name = "schedule_hour_utc")
    private Integer scheduleHourUtc;

    /** 1..7 ISO, Monday first, and only read by SCHEDULE_WEEKLY (A1). */
    @Column(name = "schedule_day_of_week")
    private Integer scheduleDayOfWeek;

    // DELIBERATELY NOT @Lob, and it must stay that way. With @Lob Hibernate binds a String as a
    // Clob, so on Postgres the column holds a large-object OID instead of the JSON and the objects
    // leak when the row goes — proven in this build, and the reason PendingChange.payloadJson
    // dropped it. columnDefinition stays, so the DDL is byte-identical either way; dropping @Lob
    // only changes the BIND (A2).
    //
    // Null means "every record of this kind", which is a real and useful rule: "email every
    // customer that is created" has no conditions at all (A2).
    @Column(name = "condition_json", columnDefinition = "TEXT")
    private String conditionJson;

    /** The ordered 1..5 entry array. Not null: a rule that does nothing is not a rule (A3). */
    @Column(name = "actions_json", nullable = false, columnDefinition = "TEXT")
    private String actionsJson;

    /** Null means act on every occasion; otherwise the same (rule, record) is left alone for N days. */
    @Column(name = "cooldown_days")
    private Integer cooldownDays;

    // @ColumnDefault so a column added to a by-then-populated table cannot repeat the D-01
    // failure, and @Builder.Default so every insert path sets it anyway (A1).
    @Column(nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "definition_version", nullable = false)
    @ColumnDefault("1")
    @Builder.Default
    private int definitionVersion = 1;

    /** Schedules only: the cross-instance claim target that claimSchedule conditions on (A1). */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // THE ACTOR. Not null, and every action this rule performs is performed as this person (A3, B2).
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    /**
     * WHERE this rule may reach, as an owned set of raw region ids.
     *
     * <p>An @ElementCollection and not a second @Entity: the DDL is the same join table the design
     * settled on, and it is one fewer class for RegionAxisRegistry to classify. EAGER because the
     * fan-out reads it off a rule on a daemon thread with no open session of its own, where a LAZY
     * set is a LazyInitializationException at the exact moment the engine is deciding what it may
     * touch (A1, B1).
     *
     * <p>NO ROWS MEANS "every region its author may manage", resolved at run time by
     * {@link RuleRegions}. It does not mean "everywhere": an author with no manage grant anywhere
     * reaches nothing, because an empty answer denies rather than widens (A1, B1).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "automation_rule_regions", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "region_id", nullable = false)
    @Builder.Default
    private Set<Long> regionIds = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Boxed, always: a primitive emits NOT NULL and the ALTER fails on a populated table. This one
    // is safe either way because the table is born empty, which is also why
    // common/RowVersionUpgrade.TABLES does NOT need automation_rules — there are no pre-existing
    // null versions to zero (B2, INTEGRATION).
    @Version
    private Long version;

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

    /** Armed and not deleted. Read LIVE at act time, never snapshotted onto a step (A1, A5). */
    public boolean live() {
        return enabled && deletedAt == null;
    }
}
