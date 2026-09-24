package com.geneinvoice.approval;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One save that was held for approval: what was asked for, by whom, in which region, measured
 * against which limit — and never the effect itself, because nothing pending has taken effect.
 * That is why no money figure and no dashboard tile anywhere changes while a change waits (B2).
 */
@Entity
@Table(name = "pending_changes",
        indexes = {@Index(name = "idx_pending_target", columnList = "target_type,target_id,status"),
                @Index(name = "idx_pending_region", columnList = "region_id,status"),
                @Index(name = "idx_pending_customer", columnList = "customer_id,status"),
                @Index(name = "idx_pending_batch", columnList = "batch_id")},
        uniqueConstraints = @UniqueConstraint(name = "uq_pending_open", columnNames = "pending_key"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingChange {

    /** Bumped whenever a payload record's shape changes incompatibly. A change raised under an
     *  older number is refused on approval rather than deserialised with fields silently dropped
     *  — Jackson does not complain about a field that no longer exists (B2). */
    public static final int PAYLOAD_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PendingAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private PendingTargetType targetType;

    // Null for a create: there is no record yet, and customer_id is what makes the waiting change
    // visible on the account it will eventually belong to (B2).
    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "customer_id")
    private Long customerId;

    // Frozen at request time and never recomputed: a change is judged in the branch it was made
    // in, so moving the account afterwards cannot walk a change into a friendlier queue (B2, B1).
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal exposure;

    // The limit this change was MEASURED against, stored rather than re-read: raising a threshold
    // must neither auto-approve what is already waiting nor re-hold what already went through (B2).
    @Column(name = "threshold_applied", nullable = false, precision = 14, scale = 2)
    private BigDecimal thresholdApplied;

    @Column(name = "always_checked", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean alwaysChecked = false;

    /** The serialised request record the maker's save carried — the same DDL as
     *  Dispute.proposedChangeJson (dispute/Dispute.java:41) and AuditLog.before_json.
     *
     *  <p>DELIBERATELY NOT &#64;Lob, and it must stay that way. Those two neighbours ARE &#64;Lob and
     *  that is a proven defect: with &#64;Lob Hibernate binds a String as a Clob, so the Postgres
     *  driver writes a LARGE OBJECT and stores its OID in the text column — `select
     *  length(after_json), after_json from audit_logs` came back `5 | 60798`. The app round-trips
     *  it, so nothing fails and no H2 test can see it, but the raw column is an unreadable
     *  integer, every payload is invisible to SQL and reporting, a plain pg_dump without -b loses
     *  them all, and the objects leak into pg_largeobject because nothing lo_unlinks them when the
     *  row is purged. columnDefinition stays, so the DDL is byte-identical either way; dropping
     *  &#64;Lob only changes the BIND. Those two neighbours have since been fixed the same way,
     *  with common/LobTextUpgrade reading the objects already written back into their own columns;
     *  this table was new, so it needed no migration at all. Do not re-add it (B2). */
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "payload_version", nullable = false)
    @Builder.Default
    private int payloadVersion = PAYLOAD_VERSION;

    /** The record as it stood when the change was raised, so a decision can be read against what
     *  the maker actually saw. DELIBERATELY NOT &#64;Lob, for the reason spelled out on payloadJson
     *  above: &#64;Lob would make Postgres store a large-object OID here instead of the JSON, the DDL
     *  would not change, and no test on H2 would ever notice. Do not re-add it (B2). */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(nullable = false, length = 300)
    private String summary;

    // The row version the maker saw. The record having moved underneath is a 409 at decision time
    // and never a silent replay onto a different record than the one that was approved (B2).
    @Column(name = "target_version")
    private Long targetVersion;

    @Column(name = "batch_id", length = 40)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PendingChangeStatus status = PendingChangeStatus.PENDING;

    /** One waiting change per record, enforced by the database and not only by a Java exists()
     *  that two concurrent makers can both pass, as DisputeService.open:61 can. NULLs never
     *  collide in a unique index on Postgres or on H2, so a decided change and every create are
     *  free — and unlike a Postgres partial index, this works on H2 and is therefore tested (B2). */
    @Column(name = "pending_key", length = 80)
    private String pendingKey;

    // Null means the engine raised it and there is no person: a rule action has no principal (B2, A5).
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_notes", length = FieldLimits.REASON)
    private String decisionNotes;

    // Boxed, the house rule: a primitive emits NOT NULL, and two approvers racing one change is
    // exactly what this counter is here to decide (B2).
    @Version
    @Column(name = "version")
    private Long version;

    /** Null for a create — a record that does not exist yet cannot already be spoken for (B2). */
    public static String keyOf(PendingTargetType type, Long targetId) {
        return targetId == null ? null : type.name() + ":" + targetId;
    }

    // The sentinel is stamped by the callback and never by a caller, so no path can leave a
    // waiting change without its key or a decided one still holding the record (B2).
    @PrePersist
    @PreUpdate
    void key() {
        pendingKey = status == PendingChangeStatus.PENDING ? keyOf(targetType, targetId) : null;
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
