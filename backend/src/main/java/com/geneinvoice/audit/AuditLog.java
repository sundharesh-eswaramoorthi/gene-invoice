package com.geneinvoice.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_audit_dispute", columnList = "dispute_id"),
        // The queue's own correlation: every row a held change produced, and every row the replay
        // wrote when it was approved, found in one read from the change rather than by matching
        // timestamps. Created from the mapping by ddl-auto and again, defensively, by
        // ApprovalSchemaUpgrade, because index creation is the least reliable part of update (B2).
        @Index(name = "idx_audit_pending", columnList = "pending_change_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false, length = 40)
    private String action;

    /**
     * The record as it stood before the change, as JSON.
     *
     * <p>NO LONGER &#64;Lob, AND IT MUST NOT COME BACK. With &#64;Lob Hibernate binds a String as a
     * Clob, so the Postgres driver writes a LARGE OBJECT and stores its OID in this text column.
     * The app round-tripped it and no H2 test could see it, but the raw column was an unreadable
     * integer — {@code select length(after_json), after_json from audit_logs} came back
     * {@code 5 | 60798} — every trail was invisible to SQL and reporting, a plain {@code pg_dump}
     * without {@code -b} lost the lot, the objects leaked into {@code pg_largeobject} because
     * nothing lo_unlinks them when a row is purged, and reading a row OUTSIDE a transaction threw
     * {@code Unable to access lob stream} because pgjdbc refuses the large-object API in
     * auto-commit mode. That last one is why four AutomationTriggerTest cases failed on a real
     * Postgres while passing on H2.
     *
     * <p>columnDefinition stays, so the DDL is byte-identical and no deployment needs a schema
     * change; dropping &#64;Lob only changes the BIND. The rows a deployment already wrote as OIDs
     * are read back and rewritten as text by {@code common/LobTextUpgrade}, which is what makes
     * this safe to drop here rather than only on the new table PendingChange.payloadJson (B2).
     */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    /**
     * The record as it stood after the change, as JSON. NOT &#64;Lob, for the reason spelled out on
     * {@link #beforeJson} above, and migrated by the same upgrade. Do not re-add it (B2).
     */
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "dispute_id")
    private Long disputeId;

    /**
     * The change this row belongs to, or null for the ~30 writes nobody had to approve.
     *
     * <p>NULLABLE ON PURPOSE, and not because most rows have no change: audit_logs is the largest
     * populated table in the product and ddl-auto:update can add a nullable bigint to it but not
     * a NOT NULL one, which under hbm2ddl.halt_on_error is the difference between a deploy and a
     * refusal to start (B2).
     */
    @Column(name = "pending_change_id")
    private Long pendingChangeId;

    @Column(length = 500)
    private String reason;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
