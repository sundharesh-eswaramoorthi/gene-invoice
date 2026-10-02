package com.geneinvoice.automation.event;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One row of the automation outbox (§3.6): something happened to a record, or a rule is due to run. Written in
 * the same transaction as the save it describes, just before it commits, and processed later by the consumer.
 *
 * <p>Nothing here can refuse a row, since a refusal would roll back the user's save (P2, R5): no foreign key, no
 * unique constraint, no enum or CHECK column type, and every bounded text the application writes is fitted to its
 * column. Identifier lists are unbounded text and are never cut.
 */
@Entity
@Table(name = "automation_events", indexes = {
        @Index(name = "idx_automation_event_due", columnList = "status,next_attempt_at"),
        @Index(name = "idx_automation_event_entity", columnList = "entity_type,entity_id,status"),
        @Index(name = "idx_automation_event_rule", columnList = "rule_id,status"),
        @Index(name = "idx_automation_event_occurred", columnList = "entity_type,occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationEvent {

    // Kinds
    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    /** Stands for an update of every invoice and payment of one customer (a rename, a region move). */
    public static final String CHILDREN = "CHILDREN";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String MANUAL = "MANUAL";
    public static final String REPLAY = "REPLAY";

    // Statuses
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String DEAD = "DEAD";

    /** The lengths the text the application writes is fitted to; the columns are at least this long. */
    public static final int CHANGES_LENGTH = 300;
    public static final int CLAIMED_BY_LENGTH = 80;
    public static final int LAST_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "entity_type", length = 20)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    /** The publishing transaction's history id: its {@code history_transactions} row says when it happened (D10). */
    @Column(name = "history_tx_id", length = 36)
    private String historyTxId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_version")
    private Long ruleVersion;

    @Lob
    @Column(name = "record_ids", columnDefinition = "TEXT")
    private String recordIds;

    @Lob
    @Column(name = "region_ids", columnDefinition = "TEXT")
    private String regionIds;

    /** Comma-joined labels of what changed (DETAILS, CREDIT, OUTSTANDING…); diagnostic only (§5.8.3). */
    @Column(length = CHANGES_LENGTH)
    private String changes;

    @Column(length = 12)
    private String origin;

    @Column(name = "caused_by_rule_id")
    private Long causedByRuleId;

    /** A default, so that {@code ddl-auto} can add the column to a table that has rows (DESIGN §3.1). */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int depth;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(nullable = false)
    private int attempts;

    /** How many times the event was claimed: the fencing token of the claim that holds it. */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int claims;

    /**
     * {@code claims} when the event was last requeued (null: never). The token only ever grows, so a worker still
     * holding a token from before the requeue stays fenced out; the claim limit counts the claims since then.
     */
    @Column(name = "claims_base")
    private Integer claimsBase;

    @Column(name = "claimed_by", length = CLAIMED_BY_LENGTH)
    private String claimedBy;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "cursor_id")
    private Long cursorId;

    @Column(name = "last_error", length = LAST_ERROR_LENGTH)
    private String lastError;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
