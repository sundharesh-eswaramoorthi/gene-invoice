package com.geneinvoice.automation.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.automation.action.ActionPreview;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The rules API (§6.14). */
public class RuleDtos {

    private RuleDtos() {}

    /** When the rule runs. {@code time}, {@code day} and {@code catchUp} are for schedules only. */
    public record TriggerDto(String kind, String entityType, String time, String day, String zone, String catchUp) {}

    /**
     * A rule as the editor sends it. The condition and the actions stay JSON until checked, so every problem can be
     * reported under its own path.
     */
    public record RuleRequest(String name, String description, Boolean enabled, TriggerDto trigger,
                              List<Long> regionIds, JsonNode condition, List<JsonNode> actions,
                              Integer maxRecordsPerRun, Long version, Boolean confirmOwnershipChange) {}

    public record OwnerDto(Long id, String name, Boolean active) {}

    public record LastRunDto(Long id, String status, Instant finishedAt, Integer matched, Integer done,
                             Integer skipped, Integer failed) {}

    /**
     * The rule as a revision keeps it: everything a user decides, nothing its runs change. As of a date, the rule is
     * shown from this.
     */
    public record RuleSnapshot(Long id, String name, String description, boolean enabled, Long version,
                               String suspendedReason, String suspendedDetail, Long ownerUserId, TriggerDto trigger,
                               List<Long> regionIds, JsonNode condition, JsonNode actions, int maxRecordsPerRun,
                               boolean deleted, Instant createdAt, Instant updatedAt) {}

    /** {@code people}: the name of each person the actions name ({@code USER} tokens), by id; gone ones left out. */
    public record RuleDto(Long id, String name, String description, boolean enabled, Long version, String status,
                          String suspendedReason, String suspendedDetail, OwnerDto owner, TriggerDto trigger,
                          List<Long> regionIds, JsonNode condition, JsonNode actions, int maxRecordsPerRun,
                          Instant nextRunAt, LastRunDto lastRun, List<String> warnings, String listLink,
                          boolean canEdit, boolean editTakesOwnership, boolean canRun, Instant createdAt,
                          Instant updatedAt, Map<Long, String> people) {}

    /** A row of the rules list. */
    public record RuleSummary(Long id, String name, boolean enabled, String status, String suspendedReason,
                              String triggerKind, String entityType, OwnerDto owner, List<Long> regionIds,
                              String lastRunStatus, Instant lastRunAt, Instant nextRunAt, Instant createdAt,
                              Instant updatedAt) {}

    public record SummaryDto(long active, long disabled, long suspended, long failedRuns24h) {}

    /** Enable (and later resume and run): a non-owner administrator says they take the rule over. */
    public record ActRequest(Boolean confirmOwnershipChange) {
        public boolean confirmed() {
            return Boolean.TRUE.equals(confirmOwnershipChange);
        }
    }

    /** Resume: whether to act on the changes made while it was suspended, and the take-over confirmation. */
    public record ResumeRequest(Boolean replay, Boolean confirmOwnershipChange) {}

    /** The rule resumed, and how many changes to its kind of record were made while it was suspended. */
    public record ResumedDto(@JsonUnwrapped RuleDto rule, long skippedWhileSuspended) {}

    /** "Run now": on these records only (at most 50), at this time (later, at most 90 days ahead). */
    public record RunRequest(List<Long> recordIds, Instant runAt, Boolean confirmOwnershipChange) {}

    /** A run asked for (a run now, a retry): its run and event, and where it stands. */
    public record QueuedRunDto(Long runId, Long eventId, String status, Instant scheduledFor) {}

    public record PreviewRequest(RuleRequest rule, Long ruleId, Long recordId, Integer sampleSize,
                                 String evaluateAs) {}

    /** One matching record; {@code hidden} when it is one only the owner can see (no id, label or link then). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SampleDto(Long id, String label, String link, Boolean hidden, List<String> alreadyDone) {
        public static SampleDto hiddenOne() {
            return new SampleDto(null, null, null, true, null);
        }
    }

    public record RecordPreviewDto(Long id, String label, boolean matches, List<ActionPreview> actions) {}

    public record PreviewDto(String entityType, String asOf, String asOfNote, String evaluatedAs, long matchCount,
                             List<SampleDto> sample, RecordPreviewDto record, List<String> problems,
                             List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MatchDto(Long id, String label, String link, Boolean hidden) {}
}
