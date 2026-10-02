package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

/** Make a task on the record; {@code title} and {@code description} take placeholders. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTaskAction(String key, Repeat repeat, String title, String description, DueSpec due,
                               List<EmailToken> assignees, boolean onlyIfNoOpenTask) implements RuleAction {

    @Override
    public ActionType type() {
        return ActionType.CREATE_TASK;
    }

    @Override
    public CreateTaskAction withKey(String key, Repeat repeat) {
        return new CreateTaskAction(key, repeat, title, description, due, assignees, onlyIfNoOpenTask);
    }

    public CreateTaskAction withAssignees(List<EmailToken> tokens) {
        return new CreateTaskAction(key, repeat, title, description, due, tokens, onlyIfNoOpenTask);
    }
}
