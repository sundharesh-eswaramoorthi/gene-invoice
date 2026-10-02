package com.geneinvoice.automation.run;

import com.geneinvoice.common.Fit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * What one action of one run did to one record (§3.6). A DONE row carries the action's dedupe key, which is unique:
 * it commits with the task, promise, dispute, email or pending change it records, so one key never acts twice (P2).
 */
@Entity
@Table(name = "automation_effects",
        uniqueConstraints = @UniqueConstraint(name = "uk_automation_effect_dedupe", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_automation_effect_run", columnList = "run_id"),
                @Index(name = "idx_automation_effect_rule", columnList = "rule_id,created_at"),
                @Index(name = "idx_automation_effect_target", columnList = "target_type,target_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationEffect {

    public static final int LABEL_MAX = 200;
    public static final int TEXT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "action_key", nullable = false, length = 36)
    private String actionKey;

    @Column(name = "action_type", nullable = false, length = 20)
    private String actionType;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_label", length = LABEL_MAX)
    private String targetLabel;

    /** {@code r{ruleId}:{actionKey}:{TARGET}:{id}}, plus {@code :e{eventId}} for an every-time action; DONE only. */
    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EffectStatus status;

    @Column(name = "result_type", length = 20)
    private String resultType;

    @Column(name = "result_id")
    private Long resultId;

    @Column(length = TEXT_MAX)
    private String message;

    @Column(length = TEXT_MAX)
    private String warnings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The key of a once-per-record action on a record: the same for every run of the rule. */
    public static String oncePerRecordKey(Long ruleId, String actionKey, String targetType, Long targetId) {
        return "r" + ruleId + ":" + actionKey + ":" + targetType + ":" + targetId;
    }

    /**
     * What one action did to one record in a run. Every text is fitted to its column, so a long customer name or
     * message can never fail the action's transaction (SC-20); warnings are kept one per line.
     */
    public static AutomationEffect of(Long runId, Long ruleId, Long eventId, String actionKey, String actionType,
                                      String targetType, Long targetId, String targetLabel, EffectStatus status,
                                      String resultType, Long resultId, String message, List<String> warnings,
                                      String dedupeKey, Instant now) {
        return AutomationEffect.builder()
                .runId(runId)
                .ruleId(ruleId)
                .eventId(eventId)
                .actionKey(actionKey)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .targetLabel(Fit.to(targetLabel, LABEL_MAX))
                .status(status)
                .resultType(resultType)
                .resultId(resultId)
                .message(Fit.to(message, TEXT_MAX))
                .warnings(warnings == null || warnings.isEmpty() ? null : Fit.to(String.join("\n", warnings), TEXT_MAX))
                .dedupeKey(status == EffectStatus.DONE ? dedupeKey : null)
                .createdAt(now)
                .build();
    }

    /** The warnings as a list, one per stored line. */
    public List<String> warningList() {
        return warnings == null || warnings.isBlank() ? List.of() : List.of(warnings.split("\n"));
    }
}
