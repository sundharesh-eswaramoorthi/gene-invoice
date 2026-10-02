package com.geneinvoice.automation.action;

import com.geneinvoice.automation.rule.TriggerEntity;

import java.util.Map;

/**
 * What one kind of action does (§5.10.5): {@code normalize} checks it at save and returns it as it is stored;
 * {@code prepare} does the read-only work before an action acts (render, resolve people, probe who can see the
 * record); {@code preview} shows that work for one record; {@code perform} acts, under the customer's lock when
 * {@link #locksCustomer()}, re-checking its guard first.
 */
public interface ActionHandler<A extends RuleAction> {

    ActionType type();

    Class<A> actionClass();

    /** The action as stored, its people checked; problems go into {@code errors} under {@code path}. */
    A normalize(A action, TriggerEntity entity, String path, Map<String, String> errors);

    Prepared prepare(A action, RecordContext rc);

    ActionPreview preview(A action, RecordContext rc);

    /**
     * The write, in the action's own transaction, with what {@link #prepare} worked out; the effect row is saved
     * with it by the caller. Handlers whose actions are carried out in a later release keep this refusal.
     */
    default ActionResult perform(A action, RecordContext rc, Prepared prepared) {
        throw new UnsupportedOperationException(type() + " actions cannot be carried out yet");
    }

    /** Whether the write needs the customer's lock (PPD-01): whatever it makes belongs to the customer. */
    default boolean locksCustomer() {
        return true;
    }
}
