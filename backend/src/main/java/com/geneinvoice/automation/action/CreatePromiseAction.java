package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

/**
 * Make a payment promise for the record's customer. No {@code collectionPoc} means the customer's own; on an invoice
 * rule {@code linkRecord} links the promise to the invoice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePromiseAction(String key, Repeat repeat, AmountSpec amount, DueSpec promisedDate,
                                  EmailToken collectionPoc, List<EmailToken> assignees, String notes,
                                  boolean linkRecord, boolean onlyIfNoOpenPromise) implements RuleAction {

    @Override
    public ActionType type() {
        return ActionType.CREATE_PROMISE;
    }

    @Override
    public CreatePromiseAction withKey(String key, Repeat repeat) {
        return new CreatePromiseAction(key, repeat, amount, promisedDate, collectionPoc, assignees, notes,
                linkRecord, onlyIfNoOpenPromise);
    }

    public CreatePromiseAction withPeople(EmailToken poc, List<EmailToken> tokens) {
        return new CreatePromiseAction(key, repeat, amount, promisedDate, poc, tokens, notes, linkRecord,
                onlyIfNoOpenPromise);
    }
}
