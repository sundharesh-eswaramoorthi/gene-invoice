package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

/**
 * Open an internal dispute on the record for someone to review: {@code proposedChange} is {@code none},
 * {@code cancel} (an invoice) or {@code void} (a payment).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateDisputeAction(String key, Repeat repeat, String reason, String proposedChange,
                                  List<EmailToken> assignees) implements RuleAction {

    public static final String NONE = "none";
    public static final String CANCEL = "cancel";
    public static final String VOID = "void";

    @Override
    public ActionType type() {
        return ActionType.CREATE_DISPUTE;
    }

    @Override
    public CreateDisputeAction withKey(String key, Repeat repeat) {
        return new CreateDisputeAction(key, repeat, reason, proposedChange, assignees);
    }

    public CreateDisputeAction withAssignees(String proposal, List<EmailToken> tokens) {
        return new CreateDisputeAction(key, repeat, reason, proposal, tokens);
    }
}
