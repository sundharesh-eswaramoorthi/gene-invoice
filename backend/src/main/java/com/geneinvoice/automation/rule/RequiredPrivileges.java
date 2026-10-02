package com.geneinvoice.automation.rule;

import com.geneinvoice.automation.action.RuleAction;
import com.geneinvoice.privilege.Privileges;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a rule's owner must hold for the rule to act (§5.10.7, SC-14): AUTOMATION_MANAGE, the entity's VIEW privilege
 * and each action's privilege. Whoever saves a rule must hold them, so saving never makes a rule that at once
 * suspends; the regions a rule is restricted to must each be one where its owner holds the regional ones.
 */
public final class RequiredPrivileges {

    private RequiredPrivileges() {}

    public static Set<String> of(TriggerEntity entity, List<RuleAction> actions) {
        Set<String> out = new LinkedHashSet<>();
        out.add(Privileges.AUTOMATION_MANAGE);
        out.add(entity.viewPrivilege());
        actions.forEach(a -> out.add(a.type().privilege()));
        return out;
    }

    public static Set<String> of(RuleDefinition rule) {
        return of(rule.entity(), rule.actions());
    }

    /** Those that are held region by region. */
    public static Set<String> regional(Set<String> privileges) {
        Set<String> out = new LinkedHashSet<>(privileges);
        out.removeIf(p -> !Privileges.isRegional(p));
        return out;
    }
}
