package com.geneinvoice.automation.rule;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The rule as it stood after one change (D8): written in the same transaction as every create, update, enable,
 * disable, suspend, resume, delete and change of owner. A rule as of a date is its latest revision saved by then;
 * the columns beside the snapshot are there to list and filter past rules without reading every snapshot.
 */
@Entity
@Table(name = "automation_rule_revisions", indexes = {
        @Index(name = "idx_rule_revision_rule", columnList = "rule_id,saved_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationRuleRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_version")
    private Long ruleVersion;

    /** The rule as {@code RuleDtos.RuleSnapshot}: everything but what its runs change. */
    @Lob
    @Column(name = "snapshot_json", columnDefinition = "TEXT", nullable = false)
    private String snapshotJson;

    @Column(nullable = false, length = AutomationRule.NAME_MAX)
    private String name;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "trigger_kind", nullable = false, length = 30)
    private String triggerKind;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "suspended_reason", length = 30)
    private String suspendedReason;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "saved_by_user_id")
    private Long savedByUserId;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;
}
