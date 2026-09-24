package com.geneinvoice.dispute;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "idx_dispute_customer", columnList = "customer_id"),
        @Index(name = "idx_dispute_target", columnList = "target_type,target_id"),
        @Index(name = "idx_dispute_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dispute implements DisputeView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "opened_by_user_id", nullable = false)
    private Long openedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private DisputeTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 2000)
    private String reason;

    /**
     * What the customer asked to have changed, as JSON.
     *
     * <p>NO LONGER &#64;Lob, AND IT MUST NOT COME BACK, for the reason spelled out in full on
     * {@code AuditLog.beforeJson}: &#64;Lob made the Postgres driver store a large-object OID in
     * this text column instead of the text. Here it also made B3's mirror lie — the reconciler
     * compares {@code disputes.proposed_change_json} against {@code dispute_history}'s copy in
     * raw SQL, and an OID never equals the JSON the mirror holds, so every dispute carrying a
     * proposed change drifted for ever on Postgres and read back as a run of digits once the
     * repair had copied the OID into the mirror. columnDefinition stays, so the DDL does not
     * change; {@code common/LobTextUpgrade} rewrites the rows a deployment already wrote (B2, B3).
     */
    @Column(name = "proposed_change_json", columnDefinition = "TEXT")
    private String proposedChangeJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DisputeStatus status = DisputeStatus.PENDING;

    @Column(name = "admin_notes", length = 2000)
    private String adminNotes;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

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
}
