package com.geneinvoice.document;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_document_entity", columnList = "entity_type,entity_id,deleted,uploaded_at"),
        @Index(name = "idx_document_customer", columnList = "customer_id"),
        @Index(name = "idx_document_storage_key", columnList = "storage_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    public static final int LABEL_MAX = 200;
    public static final int NAME_MAX = 200;
    public static final int CONTENT_TYPE_MAX = 120;
    public static final int CHECKSUM_MAX = 64;
    public static final int STORAGE_KEY_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private DocumentEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_label", nullable = false, length = LABEL_MAX)
    private String entityLabel;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false, length = FieldLimits.DOCUMENT_FILENAME)
    private String filename;

    @Column(name = "content_type", nullable = false, length = CONTENT_TYPE_MAX)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = CHECKSUM_MAX)
    private String checksum;

    @Column(name = "storage_key", nullable = false, length = STORAGE_KEY_MAX)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private DocumentVisibility visibility = DocumentVisibility.INTERNAL;

    @Column(length = FieldLimits.DOCUMENT_DESCRIPTION)
    private String description;

    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @Column(name = "uploaded_by_name", length = NAME_MAX)
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean deleted;

    @Column(name = "deleted_by_user_id")
    private Long deletedByUserId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.uploadedAt == null) {
            this.uploadedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
