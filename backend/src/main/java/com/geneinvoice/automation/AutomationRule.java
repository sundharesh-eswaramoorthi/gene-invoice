package com.geneinvoice.automation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One rule a user wrote in the UI: WHEN this kind of record is created, updated, or on a daily or
 * weekly run, WHERE it matches these filters, THEN do this (R1).
 *
 * <p>The WHERE is stored as the filter chips the list page already speaks — {@code field:op:value},
 * the same strings the filter bar puts on the query string — so a rule can ask exactly what a user
 * can ask and not one thing more, and {@link AutomationMatcher} answers it by counting rows through
 * the same executor the list page uses (R9). The THEN's parameters are a small JSON document rather
 * than a column each, because which fields matter is the action's business and a fifth action must
 * not mean five more columns on a populated table.
 *
 * <p>Both are read back and re-validated on every run: a column the schema has since dropped makes
 * the run skip with the reason on it, not the rule silently stop matching.
 */
@Entity
@Table(name = "automation_rules", indexes = {
        @Index(name = "idx_automation_rule_fires", columnList = "enabled,entity_type,trigger_kind"),
        @Index(name = "idx_automation_rule_trigger", columnList = "trigger_kind")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRule {

    public static final int NAME_MAX = 200;
    public static final int DESCRIPTION_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the rule is called on the list and in the audit trail of everything it makes. */
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    /** Why it exists, in the author's own words. Optional. */
    @Column(length = DESCRIPTION_MAX)
    private String description;

    /**
     * A disabled rule is left alone by the fan-out and by the scheduled runs, and cannot be run by
     * hand. It is the way to stop a rule without losing what it says, which is what somebody
     * actually wants at the moment a rule misfires.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private AutomationEntityType entityType;

    /**
     * Mapped to {@code trigger_kind}: TRIGGER is a reserved word in both Postgres and H2, and a
     * column called that fails the schema update rather than the query, so it would take the whole
     * service down at startup.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private TriggerKind trigger;

    /**
     * The WHERE: a JSON array of {@code field:op:value} chips, ANDed together. Empty matches every record.
     *
     * <p>Deliberately NOT {@code @Lob}. The column is {@code text} either way, but {@code @Lob}
     * makes Hibernate read it through {@code ClobJdbcType}, and a Clob's stream belongs to the
     * connection that produced it — so the consumer, which reads a rule on a background thread,
     * failed with "Unable to access lob stream" against PostgreSQL while every H2 test passed.
     * A plain String is materialised on read and is safe on any thread (D-76).
     */
    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionType action;

    /**
     * The THEN's parameters, including the assignee or recipient tokens; see
     * {@code AutomationDtos.ActionSpec}. Not {@code @Lob}, for the reason given on
     * {@link #filtersJson} (D-76).
     */
    @Column(name = "action_json", columnDefinition = "TEXT")
    private String actionJson;

    /**
     * Who wrote it. Kept as an id with no foreign key, like a dispute's opener, so deactivating the
     * author never breaks the rule — and it is the user the rule's actions are attributed to, since
     * a run has nobody logged in to attribute them to instead (R7).
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /** When a run of this rule last did something, so the list can say whether it is actually used. */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** How many times the THEN has actually happened — not how many records were looked at. */
    @Column(name = "run_count", nullable = false)
    @Builder.Default
    private Long runCount = 0L;

    @Column(updatable = false)
    private Instant createdAt;

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
