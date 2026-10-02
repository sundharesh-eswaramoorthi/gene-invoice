package com.geneinvoice.automation.rule;

import com.geneinvoice.automation.action.RuleAction;
import com.geneinvoice.common.query.Condition;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * A rule as the engine reads it: checked, parsed and ready to evaluate, whether it came from a save, a stored rule or
 * a revision. {@code id} is null for a rule not yet saved; {@code regionIds} empty means every region where the owner
 * may act.
 */
public record RuleDefinition(Long id, String name, String description, boolean enabled, TriggerKind triggerKind,
                             TriggerEntity entity, String scheduleTime, String scheduleDay, ZoneId zone,
                             String catchUp, Set<Long> regionIds, Condition condition, List<RuleAction> actions,
                             int maxRecordsPerRun, Long ownerUserId) {

    public RuleDefinition {
        regionIds = Set.copyOf(regionIds);
        actions = List.copyOf(actions);
    }

    public boolean restricted() {
        return !regionIds.isEmpty();
    }
}
