package com.geneinvoice.approval;

import com.geneinvoice.common.Fit;
import com.geneinvoice.region.Region;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A money change held until someone else approves it (B2, §3.8). Not versioned: its as-of answer comes from its
 * own {@code requested_at}/{@code decided_at} interval (D8). Every string is fitted to its column, so a long
 * customer name can never fail the maker's held save (SC-20).
 */
@Entity
@Table(name = "pending_changes",
        uniqueConstraints = @UniqueConstraint(name = "uk_pending_outstanding", columnNames = "outstanding_key"),
        indexes = {
                @Index(name = "idx_pending_status_region", columnList = "status,region_id"),
                @Index(name = "idx_pending_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_pending_affected", columnList = "affected_type,affected_id"),
                @Index(name = "idx_pending_customer", columnList = "customer_id,status"),
                @Index(name = "idx_pending_maker", columnList = "maker_user_id,status"),
                @Index(name = "idx_pending_requested", columnList = "requested_at"),
                @Index(name = "idx_pending_decided", columnList = "decided_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingChange {

    public static final int ENTITY_LABEL_MAX = 80;
    public static final int CUSTOMER_NAME_MAX = 150;
    public static final int SUMMARY_MAX = 300;
    public static final int NOTE_MAX = 500;
    public static final int KEY_MAX = 60;
    public static final int PAYLOAD_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = ApprovalConverters.OperationName.class)
    @Column(nullable = false, length = 40)
    private ApprovalOperation operation;

    @Convert(converter = ApprovalConverters.StatusName.class)
    @Column(nullable = false, length = 20)
    private PendingStatus status;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    /** Null for a create. */
    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "entity_label", length = ENTITY_LABEL_MAX)
    private String entityLabel;

    /** The record whose money state changes: the entity itself, or a dispute's target. Null for a create. */
    @Column(name = "affected_type", length = 30)
    private String affectedType;

    @Column(name = "affected_id")
    private Long affectedId;

    @Column(name = "customer_id")
    private Long customerId;

    /** The customer's name when the change was asked for. */
    @Column(name = "customer_name", length = CUSTOMER_NAME_MAX)
    private String customerName;

    /** True when the change came from an internal (automation) dispute: never shown to a customer login (SC-1). */
    @Column(name = "internal_source", nullable = false)
    @Builder.Default
    private boolean internalSource = false;

    /** The record's region when the change was asked for; never changes (D7). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false, updatable = false)
    private Region region;

    @Column(name = "record_poc_user_id")
    private Long recordPocUserId;

    /** The change's own measure. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** The limit it was above. */
    @Column(name = "threshold_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(nullable = false, length = SUMMARY_MAX)
    private String summary;

    /** What the approval re-runs the service method with; no password ever lands here. */
    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "payload_version", nullable = false)
    private int payloadVersion;

    @Lob
    @Column(name = "base_json", columnDefinition = "TEXT")
    private String baseJson;

    @Lob
    @Column(name = "proposed_json", columnDefinition = "TEXT")
    private String proposedJson;

    /** {@code "<ENTITY>:<id>"} while PENDING on an existing record, otherwise null: one outstanding per record. */
    @Column(name = "outstanding_key", length = KEY_MAX)
    private String outstandingKey;

    @Convert(converter = ApprovalConverters.OriginName.class)
    @Column(nullable = false, length = 12)
    private ChangeOrigin origin;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "automation_event_id")
    private Long automationEventId;

    @Column(name = "maker_user_id", nullable = false)
    private Long makerUserId;

    /** From the clock, never a callback: the approval of a payment keeps it as the time of receipt. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    /** Set on every move to a final status. */
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = NOTE_MAX)
    private String decisionNote;

    @Column(name = "failure_reason", length = NOTE_MAX)
    private String failureReason;

    @Column(name = "superseded_by_id")
    private Long supersededById;

    @Column(name = "result_entity_id")
    private Long resultEntityId;

    @Lob
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Version
    private Long version;

    /** A new PENDING change for this gated request. */
    public static PendingChange pending(Gated g, Region region, BigDecimal threshold, Long makerUserId,
                                        ChangeOrigin origin, Long ruleId, Long eventId, String payloadJson,
                                        String baseJson, String proposedJson, Instant now) {
        return PendingChange.builder()
                .operation(g.op())
                .status(PendingStatus.PENDING)
                .entityType(g.op().entityType())
                .entityId(g.entityId())
                .entityLabel(Fit.to(g.entityLabel(), ENTITY_LABEL_MAX))
                .affectedType(g.affectedType())
                .affectedId(g.affectedId())
                .customerId(g.customerId())
                .customerName(Fit.to(g.customerName(), CUSTOMER_NAME_MAX))
                .region(region)
                .recordPocUserId(g.recordPocUserId())
                .amount(g.amount())
                .thresholdAmount(threshold)
                .summary(Fit.to(g.summary(), SUMMARY_MAX))
                .payloadJson(payloadJson)
                .payloadVersion(PAYLOAD_VERSION)
                .baseJson(baseJson)
                .proposedJson(proposedJson)
                .outstandingKey(g.outstandingKey())
                .origin(origin)
                .ruleId(ruleId)
                .automationEventId(eventId)
                .makerUserId(makerUserId)
                .requestedAt(now)
                .build();
    }

    public Long getRegionId() {
        return Region.idOf(region);
    }

    /** The key it held while it was pending, worked out again once it has been let go. */
    public String outstandingKeyAtRequest() {
        return Gated.outstandingKey(operation, entityId);
    }

    public boolean isPending() {
        return status == PendingStatus.PENDING;
    }

    public void approve(Long checkerUserId, String note, Instant now, Long resultEntityId, String resultJson) {
        decide(PendingStatus.APPROVED, checkerUserId, note, now);
        this.resultEntityId = resultEntityId;
        this.resultJson = resultJson;
    }

    public void reject(Long checkerUserId, String note, Instant now) {
        decide(PendingStatus.REJECTED, checkerUserId, note, now);
    }

    public void withdraw(Long makerUserId, Instant now) {
        decide(PendingStatus.WITHDRAWN, makerUserId, null, now);
    }

    public void fail(Long checkerUserId, String reason, Instant now) {
        decide(PendingStatus.FAILED, checkerUserId, null, now);
        this.failureReason = Fit.to(reason, NOTE_MAX);
    }

    /** Replaced by the same maker's newer request for the same record; frees the outstanding key. */
    public void supersede(Long makerUserId, Instant now) {
        decide(PendingStatus.SUPERSEDED, makerUserId, null, now);
    }

    /** Closed by what happened to its record (deleted, moved, resolved); nobody decided it. */
    public void close(String reason, Instant now) {
        decide(PendingStatus.CLOSED, null, reason, now);
    }

    private void decide(PendingStatus to, Long by, String note, Instant now) {
        if (status != PendingStatus.PENDING) {
            throw new IllegalStateException("Pending change " + id + " is already " + status);
        }
        this.status = to;
        this.decidedByUserId = by;
        this.decidedAt = now;
        this.decisionNote = Fit.to(note, NOTE_MAX);
        this.outstandingKey = null;
    }
}
