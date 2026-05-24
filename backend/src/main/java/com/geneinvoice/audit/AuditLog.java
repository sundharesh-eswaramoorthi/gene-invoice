package com.geneinvoice.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_audit_dispute", columnList = "dispute_id")
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

    @Lob
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Lob
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(name = "dispute_id")
    private Long disputeId;

    @Column(length = 500)
    private String reason;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
