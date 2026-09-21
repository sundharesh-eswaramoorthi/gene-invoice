package com.geneinvoice.automation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.email.EmailDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class AutomationDtos {

    /**
     * The THEN's parameters, stored as {@code automation_rules.action_json} and read back on every
     * run (R6). One record answers for all four actions rather than a column each: what a rule
     * needs to say is a line of text, a body, a date offset, an amount and who it is for, and which
     * of those matter is the action's own business — checked when the rule is written, by
     * {@code AutomationService}, so a rule that cannot act is a 400 at authoring rather than a
     * skipped run nobody is watching (R9).
     *
     * <p>Nothing here is read off the record at run time. A rule that guessed the promised amount
     * from an invoice's balance would commit a customer to a number no person ever agreed, so the
     * amount is stated by whoever wrote the rule or the rule is not accepted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionSpec(
            /** The task's title or the email's subject. Required for both. */
            @Size(max = FieldLimits.EMAIL_SUBJECT) String title,
            /** The email's body, the task's notes, the promise's notes, or the dispute's reason. */
            @Size(max = FieldLimits.EMAIL_BODY) String body,
            /** Days from the run to the task's due date or the promise's promised date. */
            Integer dueInDays,
            /** The promised amount. Required for CREATE_PROMISE; see the note above. */
            BigDecimal amount,
            /**
             * The email's From, picked exactly as the compose form picks one. Required for a
             * SEND_EMAIL rule and refused at authoring when it is missing: a rule has no caller to
             * send as, so an unnamed sender is not a default but an email that can never go (R7).
             */
            EmailDtos.EmailToken from,
            /**
             * Who the action is for, picked exactly as everywhere else: a person, or a role at a
             * level. The task's or promise's or dispute's assignees, or the email's To — one field,
             * because it is one picker.
             */
            List<EmailDtos.EmailToken> assignees,
            /** The task's opening status; omitted means whatever a task opens as by default. */
            String status
    ) {}

    /**
     * A rule as the UI writes it. {@code filters} are the same {@code field:op:value} chips the
     * list page puts on its query string, and are validated against that list's own schema here and
     * now, so a rule that could never match is refused rather than written (R9).
     */
    public record CreateRuleRequest(
            @NotBlank @Size(max = AutomationRule.NAME_MAX) String name,
            @Size(max = AutomationRule.DESCRIPTION_MAX) String description,
            /** Omitted means enabled: somebody writing a rule means it to run. */
            Boolean enabled,
            @NotBlank String entityType,
            @NotBlank String trigger,
            List<String> filters,
            @NotBlank String action,
            @Valid ActionSpec actionSpec
    ) {}

    /** The same fields; an update states the whole rule, as the form does. */
    public record UpdateRuleRequest(
            @NotBlank @Size(max = AutomationRule.NAME_MAX) String name,
            @Size(max = AutomationRule.DESCRIPTION_MAX) String description,
            Boolean enabled,
            @NotBlank String entityType,
            @NotBlank String trigger,
            List<String> filters,
            @NotBlank String action,
            @Valid ActionSpec actionSpec
    ) {}

    /**
     * A rule as it reads back. {@code triggerLabel} and {@code actionLabel} are the words the list
     * puts in its sentence, built here so one service decides how a rule reads rather than each
     * screen wording it again.
     *
     * <p>The assignee tokens come back exactly as they were picked, and are not resolved into
     * people: a rule has no record behind it, so there is no customer whose POC book could answer
     * who a role reaches. Who it reaches is settled on the run, against the record it ran on (A2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleDto(
            Long id,
            String name,
            String description,
            boolean enabled,
            AutomationEntityType entityType,
            TriggerKind trigger,
            String triggerLabel,
            List<String> filters,
            ActionType action,
            String actionLabel,
            ActionSpec actionSpec,
            Long createdByUserId,
            Instant lastRunAt,
            long runCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * One outbox row as the runs list shows it: which record the rule was asked about and what came
     * of it. SKIPPED is the ordinary answer and says why in {@code lastError} — "the filters did not
     * match" is information, not a fault.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleRunDto(
            Long id,
            Long ruleId,
            AutomationEntityType entityType,
            Long entityId,
            TriggerKind trigger,
            AutomationEventStatus status,
            int attempts,
            /** Why it skipped or failed; null while it is still going or when it simply worked. */
            String lastError,
            Instant enqueuedAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * What Run now queued. {@code cap} is the ceiling it stopped at, so a rule that matches more
     * records than one run may take says so instead of quietly doing part of the job.
     */
    public record RunResultDto(int queued, int matched, int cap, boolean capped) {}
}
