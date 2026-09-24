package com.geneinvoice.automation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.geneinvoice.email.EmailDtos;

import java.math.BigDecimal;
import java.util.List;

/**
 * One thing a rule does, in the shape it is stored in {@code automation_rules.actions_json} and
 * the shape it crosses the wire in — the same shape, deliberately, so what a rule was saved with
 * is what a rule is answered with (A3).
 *
 * <p>{@code EmailDtos.EmailToken} is reused VERBATIM for assignees, recipients, the sender and a
 * promise's collection POC. It is the token the To field already sends and the Flutter
 * {@code EmailToken} already round-trips with {@code ==}/{@code hashCode}, so the assignee picker
 * is wire-compatible with the To picker BY CONSTRUCTION rather than by two sides agreeing to stay
 * in step (A3).
 *
 * <p>The array is ORDERED and the order is significant: a later action waits for an earlier one,
 * which is how "create the task, then email the customer about it" is expressible at all. One to
 * five entries; the cap is on the rule and not on the consumer, because a rule with fifty actions
 * is a mistake at authoring time and a runaway at run time (A3, A5).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionSpec.CreateTask.class, name = "CREATE_TASK"),
        @JsonSubTypes.Type(value = ActionSpec.CreatePromise.class, name = "CREATE_PROMISE"),
        @JsonSubTypes.Type(value = ActionSpec.CreateDispute.class, name = "CREATE_DISPUTE"),
        @JsonSubTypes.Type(value = ActionSpec.SendEmail.class, name = "SEND_EMAIL")})
public sealed interface ActionSpec
        permits ActionSpec.CreateTask, ActionSpec.CreatePromise,
        ActionSpec.CreateDispute, ActionSpec.SendEmail {

    /**
     * The discriminator, as a Java value.
     *
     * <p>{@code @JsonIgnore} because Jackson writes the very same word itself from the
     * {@code @JsonTypeInfo} above: without it a serialised action would carry "kind" twice, and
     * the duplicate is the one that would later fail to parse (A3).
     */
    @JsonIgnore
    ActionKind kind();

    /**
     * Assignees are role-at-a-level tokens for the same reason recipients are: "the collection
     * POC on this account" is a seat and not a person, and a rule written against a person breaks
     * silently the day that person changes desks (A6, A3).
     */
    record CreateTask(String title, String notes, List<EmailDtos.EmailToken> assignees,
                      Integer dueInDays) implements ActionSpec {
        @Override
        public ActionKind kind() {
            return ActionKind.CREATE_TASK;
        }
    }

    record CreatePromise(AmountSource amountFrom, BigDecimal fixedAmount, Integer promisedInDays,
                         EmailDtos.EmailToken collectionPoc, String notes) implements ActionSpec {
        @Override
        public ActionKind kind() {
            return ActionKind.CREATE_PROMISE;
        }
    }

    /** A rule proposes nothing: proposedChangeJson is always null on an automated dispute (A3). */
    record CreateDispute(String reason) implements ActionSpec {
        @Override
        public ActionKind kind() {
            return ActionKind.CREATE_DISPUTE;
        }
    }

    /**
     * {@code from} is never absent and is never a CUSTOMER token: a rule has no signed-in user to
     * fall back on, so the sender is always explicit (A3).
     */
    record SendEmail(EmailDtos.EmailToken from, List<EmailDtos.EmailToken> to,
                     String subject, String body) implements ActionSpec {
        @Override
        public ActionKind kind() {
            return ActionKind.SEND_EMAIL;
        }
    }

    /** Where a promised amount comes from. FIXED is the only one that reads fixedAmount (A3). */
    enum AmountSource {
        INVOICE_BALANCE,
        CUSTOMER_OUTSTANDING,
        FIXED
    }
}
