package com.geneinvoice.automation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.UUID;

/**
 * The outbox (R1). One row is written INSIDE the transaction that saved the record, so it commits
 * with the save and never before it: a consumer can never be handed a record that does not exist
 * yet, and nothing is lost if the consumer is down, because the row is still there when it comes
 * back. Publishing is what happens after the commit, best-effort; the row is the truth and
 * {@link AutomationScheduler}'s sweep is the guarantee (R8).
 *
 * <p>Rows come in two shapes, which is what {@code ruleId} says (R2):
 * <ul>
 *   <li><b>Fan-out</b> ({@code ruleId} null) — written by {@link AutomationEvents} as the user
 *       saves. It names only what happened, because the save is not the place to read the rule
 *       table: that would be a second query on every write, and it would still race a rule written
 *       between the read and the commit. Its whole job on the consumer is to insert one rule row
 *       per rule that now applies.</li>
 *   <li><b>Rule</b> ({@code ruleId} set) — one rule against one record. This is the row whose
 *       consumption actually creates a task or sends an email.</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} is unique, and that index is the whole answer to double delivery (R4).
 * A rule row's key is the rule, the record, the trigger and a bucket, so asking for the same work
 * twice — the sweeper republishing, a cron firing twice, somebody pressing Run now again — is one
 * insert that wins and one that clashes and is dropped. A fan-out row's key ends in a UUID and is
 * therefore unique by construction, deliberately: it is inserted inside the user's transaction,
 * and a unique-constraint violation there would mark that transaction rollback-only and fail the
 * user's save. The duplication it lets through is absorbed one stage later, by the rule rows.
 *
 * <p>A key is held for as long as the row might stand for something that happened, and no longer.
 * A row that settles SKIPPED gives its key up — see {@link #releasedKey} — because a run that did
 * nothing has nothing to de-duplicate against.
 */
@Entity
@Table(name = "automation_events", indexes = {
        @Index(name = "idx_automation_event_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_automation_event_due", columnList = "status,next_attempt_at"),
        @Index(name = "idx_automation_event_rule", columnList = "rule_id,id"),
        @Index(name = "idx_automation_event_record", columnList = "entity_type,entity_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationEvent {

    public static final int KEY_MAX = 200;
    public static final int ERROR_MAX = 2000;
    /** What {@link #releasedKey} stamps on a key that no longer claims its bucket. */
    private static final String RELEASED = "|skipped#";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The rule this row is for; null on a fan-out row, which has not chosen its rules yet. */
    @Column(name = "rule_id")
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private AutomationEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private TriggerKind trigger;

    /** What makes this piece of work the same piece of work; see the class note. */
    @Column(name = "idempotency_key", nullable = false, length = KEY_MAX)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AutomationEventStatus status = AutomationEventStatus.QUEUED;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Why it skipped or failed, for the runs list. Null while it is still going. */
    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;

    /**
     * When the row was last handed to the transport — not when it was queued. Null means nobody has
     * published it, or a retry has un-published it, which is what tells the sweeper to publish it
     * again rather than leave it to a nudge that may never have arrived.
     */
    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    /** The earliest a retry may be claimed; null means now. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ---- the natural key --------------------------------------------------------------

    /**
     * The key of one rule against one record (R4). The bucket is what makes "the same work" mean
     * something over time:
     * <ul>
     *   <li>CREATED — a constant. A record is created once, so a second CREATED row for it is a
     *       repeat delivery and nothing else, whenever it turns up.</li>
     *   <li>UPDATED — the UTC day. Somebody correcting an invoice five times in an afternoon wants
     *       one task out of it, not five; once per record per day is the line, and it is drawn here
     *       rather than in the action so every action gets it.</li>
     *   <li>DAILY — the UTC day, so a restart, a second scheduler or a cron that fires twice all
     *       come to the same day's work.</li>
     *   <li>WEEKLY — the ISO week, for the same reason.</li>
     * </ul>
     * UTC and not the viewer's zone: there is no viewer on a consumer thread, and a boundary that
     * moves with whoever is looking is not a key.
     *
     * <p>A bucket collapses repeated matches, not repeated askings: a row that is asked and answers
     * no releases the key again as it settles ({@link #releasedKey}).
     */
    public static String ruleKey(Long ruleId, AutomationEntityType type, Long entityId,
                                 TriggerKind trigger, Instant now) {
        return ruleId + "|" + type.name() + "|" + entityId + "|" + trigger.name() + "|"
                + bucket(trigger, now);
    }

    /**
     * The key of a fan-out row. Unique by construction — see the class note on why this one must
     * never be able to clash.
     */
    public static String fanOutKey(AutomationEntityType type, Long entityId, TriggerKind trigger) {
        return "*|" + type.name() + "|" + entityId + "|" + trigger.name() + "|" + UUID.randomUUID();
    }

    /**
     * The same key, stamped so that it no longer claims its bucket. This is what a rule row does as
     * it settles SKIPPED, and it is the difference between de-duplicating ACTIONS and
     * de-duplicating EVALUATIONS.
     *
     * <p>The key is claimed when the work is queued, which is before the WHERE has been looked at:
     * {@link AutomationEvents#enqueueForRule} knows the rule and the record and nothing else, and
     * the filters are not read until {@link AutomationWorker} runs the row. Leaving a non-match
     * holding the key meant the FIRST edit of a record spent that record's only slot for the rule
     * that day, so an invoice edited to 100 in the morning and to 600 in the afternoon never
     * chased: the afternoon's work found the morning's refusal sitting in the unique index, and
     * nothing was queued until the day rolled over (D-72, regression 2026-09-21).
     *
     * <p>Only SKIPPED gives the key up. DONE keeps it, which is the bucket doing its real job —
     * correcting an invoice five times in an afternoon is one task and not five (R4) — and FAILED
     * keeps it too, because a failed run may have acted and a second one on top of it is the
     * outcome this whole design exists to avoid. That leaves the double delivery the key was
     * written for still covered: a row is claimed before it is worked, so it holds its key from the
     * moment it is queued until the moment it says it did nothing, and a sweeper republishing the
     * same event inside that window finds the key taken. What a release lets through is a second
     * EVALUATION of a record that matched nothing, which costs a count query and a row on the runs
     * list and creates nothing at all. The promise the bucket makes is kept exactly: one row holds
     * the key at a time, and only a row that did not act ever lets go of it, so a bucket is still
     * at most one action.
     *
     * <p>The row's own id is what makes the stamped key unique, so this is a local edit under the
     * lock the settle already holds rather than another read of the table, and the released key
     * still reads as the work it was.
     */
    public static String releasedKey(String key, Long eventId) {
        if (key == null || key.contains(RELEASED)) return key;
        String stamp = RELEASED + eventId;
        String head = key.length() + stamp.length() <= KEY_MAX
                ? key
                : key.substring(0, KEY_MAX - stamp.length());
        return head + stamp;
    }

    static String bucket(TriggerKind trigger, Instant now) {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return switch (trigger) {
            case CREATED -> "once";
            case UPDATED, DAILY -> day.toString();
            case WEEKLY -> day.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                    + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        };
    }
}
