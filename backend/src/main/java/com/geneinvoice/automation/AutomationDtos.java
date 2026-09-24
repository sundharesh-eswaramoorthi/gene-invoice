package com.geneinvoice.automation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.asof.AsOfInfo;
import com.geneinvoice.email.Placeholders;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class AutomationDtos {

    /** A branch a rule names, with the code and name a client renders (A1, B1). */
    public record RuleRegionDto(Long id, String code, String name) {}

    /**
     * The rule, as the list row AND as the detail page — one shape, because a rule is small and a
     * second trimmed DTO is a second place for the two to drift apart (A1).
     *
     * <p>{@code conditions} is the JSON TREE and not a string: the client's condition editor reads
     * it as structure, and what comes back is the canonical chip form
     * {@code ConditionJson.write} produced from whatever was sent, so a saved rule and a typed
     * filter are one grammar (A2).
     *
     * <p>{@code regions} is what the rule NAMED. An empty list is a real and different state from
     * a named one — it means "every branch my author may manage, as of now" — and
     * {@code allAuthorRegions} says so in a word so the client does not have to infer it from an
     * absence (A1, B1).
     */
    public record RuleDto(
            Long id,
            String name,
            String description,
            SubjectType subjectType,
            TriggerKind triggerKind,
            Integer scheduleHourUtc,
            Integer scheduleDayOfWeek,
            JsonNode conditions,
            List<ActionSpec> actions,
            Integer cooldownDays,
            boolean enabled,
            int definitionVersion,
            List<RuleRegionDto> regions,
            boolean allAuthorRegions,
            Instant nextRunAt,
            Instant lastRunAt,
            Long createdByUserId,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            Long version) {}

    /**
     * One row of the run history. {@code producedType} + {@code producedId} is what turns a
     * finished step into a link to the task, promise, dispute or email it made (A5).
     */
    public record StepDto(
            Long id,
            Instant createdAt,
            Long ruleId,
            String ruleName,
            int ruleVersion,
            SubjectType subjectType,
            Long subjectId,
            Long customerId,
            String customerName,
            ActionKind actionKind,
            int actionIndex,
            StepSource source,
            StepStatus status,
            int attempts,
            Long runId,
            String occasion,
            ProducedType producedType,
            Long producedId,
            String unresolved,
            String result,
            Instant finishedAt,
            Long regionId,
            String regionName) {}

    /**
     * What a rule is. Conditions arrive as the JSON tree the condition editor holds; null and an
     * empty object both mean "every record of this kind" (A1, A2).
     */
    public record SaveRuleRequest(
            @NotBlank @Size(max = FieldLimits.RULE_NAME) String name,
            @Size(max = FieldLimits.RULE_DESCRIPTION) String description,
            @NotNull SubjectType subjectType,
            @NotNull TriggerKind triggerKind,
            Integer scheduleHourUtc,
            Integer scheduleDayOfWeek,
            JsonNode conditions,
            List<ActionSpec> actions,
            Integer cooldownDays,
            Boolean enabled,
            List<Long> regionIds) {}

    /**
     * Render one template against ONE record, for the builder's live preview.
     *
     * <p>The record is read through the ordinary gate BEFORE anything is rendered: a placeholder
     * that resolves a customer's name is a read of that customer, and a preview must not be a way
     * to read one across a region boundary (A4, AUTH-08).
     */
    public record PreviewRequest(
            @NotNull SubjectType subjectType,
            @NotNull Long subjectId,
            String title,
            String subject,
            String body) {}

    /** One rendered template plus the tokens that had nothing behind them on this record (A4). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderedDto(String text, List<String> unresolved) {}

    /**
     * {@code asOf} is stated rather than assumed: until temporal storage exists the renderer reads
     * the LIVE record, so the preview says "values as of today" instead of pretending (A4, B3).
     */
    public record PreviewResponse(
            SubjectType subjectType,
            Long subjectId,
            String subjectLabel,
            LocalDate asOf,
            RenderedDto title,
            RenderedDto subject,
            RenderedDto body) {}

    /**
     * "Run now".
     *
     * <p>{@code requestId} is THE DOUBLE-CLICK GUARD and it is required: the occasion is
     * {@code "M" + requestId}, so the second click of the same click carries the same key and
     * bounces off {@code uk_run_occasion} instead of opening a second run. A guard the client may
     * omit is a guard that is not there (A5).
     *
     * <p>{@code asOf} may also be given on the query string, which is the form the design writes;
     * the query wins when both are present.
     */
    public record RunRequest(@Size(max = 60) String requestId, LocalDate asOf) {}

    /**
     * One record a dry run would act on, as THE PERSON WHO ASKED would see it.
     *
     * <p>A record inside the rule's reach but outside the caller's own is counted in
     * {@code matched} and left out of the sample: the count is a fact about the rule, and a
     * rendered customer name is a read of that customer (A5, AUTH-08).
     */
    public record SampleDto(Long id, String label, String renderedTitle, String renderedSubject) {}

    /** What would happen, having written nothing at all (A5). */
    public record DryRunDto(long matched, boolean truncated, List<SampleDto> sample) {}

    /**
     * WHAT THIS RULE WOULD HAVE DONE ON THAT DAY, having written nothing at all (B3).
     *
     * <p>A dry run's two figures plus the stamp every other as-of answer in the application
     * carries, so a backtest says which day it answered in exactly the shape a list, a tile, a
     * figure and an export already say it: {@code {date, floor, exact, origin, appliesTo,
     * omittedDeleted, notes[]}}, and null when the date asked for was today or later and the
     * request was served live.
     *
     * @param sampleRendersTodaysValues ALWAYS TRUE TODAY, AND ON THE WIRE BECAUSE IT IS A CAVEAT
     *        AND NOT A SETTING. The matched SET is as of the date; the previewed text beside each
     *        record is rendered from the LIVE record, with only the date arithmetic
     *        ({@code {{Invoice.DaysOverdue}}}) counted from the as-of day. B3's design says
     *        placeholders should render from the as-of record when simulating, and they do not:
     *        {@code Placeholders} pattern-matches the concrete {@code Invoice} and {@code Payment}
     *        classes, so a mirror row renders every slot null, and widening it to the {@code *View}
     *        interfaces is an edit to A4's catalogue on the live render path that this unit does
     *        not own. A client must not put this text in front of somebody as the values of that
     *        day, and the flag is here so it cannot be read as them by accident (A4, B3).
     */
    public record SimulationDto(long matched, boolean truncated, AsOfInfo asOf,
                                boolean sampleRendersTodaysValues, List<SampleDto> sample) {}

    /**
     * One firing, returned the moment it is queued — the sweeper drains it.
     *
     * <p>This is why there is no {@code GET /api/automation/runs}: the run history is read through
     * the {@code automationSteps} schema filtered by {@code runId}, which is region-scoped by the
     * step's own axis and needs no second read surface (A5, B1).
     */
    public record RunDto(Long id, Long ruleId, String ruleName, StepSource source, String occasion,
                         RunStatus status, int matched, boolean truncated, int stepsPlanned,
                         Instant startedAt, Instant finishedAt, String error) {

        static RunDto of(AutomationRun run) {
            return new RunDto(run.getId(), run.getRuleId(), run.getRuleName(), run.getSource(),
                    run.getOccasion(), run.getStatus(), run.getMatched(), run.isTruncated(),
                    run.getStepsPlanned(), run.getStartedAt(), run.getFinishedAt(), run.getError());
        }
    }

    /** The placeholder catalogue, passed through exactly as the email layer builds it (A4). */
    public record PlaceholderDto(String key, String label, String group, String type, String example) {
        public static PlaceholderDto of(Placeholders.Slot slot) {
            return new PlaceholderDto(slot.key(), slot.label(), slot.group(),
                    slot.type() == null ? null : slot.type().name(), slot.example());
        }
    }
}
