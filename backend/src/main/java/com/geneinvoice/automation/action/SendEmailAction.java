package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

/**
 * Send an email about the record. No {@code from} means the rule's owner; a role sender nobody holds falls back to
 * the owner when {@code fallbackToOwner}. {@code subject} and {@code body} take placeholders.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendEmailAction(String key, Repeat repeat, EmailToken from, boolean fallbackToOwner,
                              List<EmailToken> to, String subject, String body) implements RuleAction {

    @Override
    public ActionType type() {
        return ActionType.SEND_EMAIL;
    }

    @Override
    public SendEmailAction withKey(String key, Repeat repeat) {
        return new SendEmailAction(key, repeat, from, fallbackToOwner, to, subject, body);
    }

    public SendEmailAction withPeople(EmailToken sender, List<EmailToken> recipients) {
        return new SendEmailAction(key, repeat, sender, fallbackToOwner, recipients, subject, body);
    }
}
