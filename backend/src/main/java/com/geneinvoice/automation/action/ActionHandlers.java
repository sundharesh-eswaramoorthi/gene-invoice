package com.geneinvoice.automation.action;

import com.geneinvoice.automation.rule.TriggerEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The handler of each kind of action. */
@Component
public class ActionHandlers {

    private final Map<Class<?>, ActionHandler<?>> byClass;

    public ActionHandlers(List<ActionHandler<?>> handlers) {
        this.byClass = handlers.stream().collect(Collectors.toMap(ActionHandler::actionClass, Function.identity()));
    }

    @SuppressWarnings("unchecked")
    public <A extends RuleAction> ActionHandler<A> of(A action) {
        ActionHandler<A> handler = (ActionHandler<A>) byClass.get(action.getClass());
        if (handler == null) throw new IllegalStateException("No handler for " + action.type());
        return handler;
    }

    public RuleAction normalize(RuleAction action, TriggerEntity entity, String path,
                                Map<String, String> errors) {
        return of(action).normalize(action, entity, path, errors);
    }

    public ActionPreview preview(RuleAction action, RecordContext rc) {
        return of(action).preview(action, rc);
    }

    public Prepared prepare(RuleAction action, RecordContext rc) {
        return of(action).prepare(action, rc);
    }

    public ActionResult perform(RuleAction action, RecordContext rc, Prepared prepared) {
        return of(action).perform(action, rc, prepared);
    }

    public boolean locksCustomer(RuleAction action) {
        return of(action).locksCustomer();
    }
}
