package com.geneinvoice.email;

import com.geneinvoice.common.FieldLimits;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * One email about one record, sent or received. The record and the people are referenced by id
 * only, like a dispute's opener, so deleting or deactivating either never breaks the history; the
 * label and names are snapshots taken when the email was saved. An update writes only the columns
 * it changed, so a writer never puts back a column another changed since it read the row.
 */
@Entity
@DynamicUpdate
@Table(name = "emails", indexes = {
        @Index(name = "idx_email_entity", columnList = "entity_type,entity_id,occurred_at"),
        @Index(name = "idx_email_thread", columnList = "provider_thread_id"),
        @Index(name = "idx_email_rfc_message", columnList = "rfc_message_id"),
        @Index(name = "idx_email_dispatch", columnList = "status,next_attempt_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Email {

    public static final int LABEL_MAX = 200;
    public static final int NAME_MAX = 200;
    public static final int ADDRESS_MAX = 320;
    public static final int UNRESOLVED_MAX = 1000;
    public static final int ERROR_MAX = 1000;
    public static final int HEADER_ID_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EmailEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_label", nullable = false, length = LABEL_MAX)
    private String entityLabel;

    /**
     * True once the record this email was recorded against has been deleted. The emails table
     * carries the entity as a type and an id with no foreign key, so nothing else would tell a
     * reader that the customer behind {@code entity_id} is gone, and the row went on offering a
     * link to a 404 (CP-13). Mapped nullable, with null read as false, so {@code ddl-auto:
     * update} can add the column to a table that already has rows.
     */
    @Column(name = "entity_deleted")
    private Boolean entityDeleted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmailDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EmailStatus status;

    @Column(nullable = false, length = FieldLimits.EMAIL_SUBJECT)
    private String subject;

    @Column(nullable = false, length = FieldLimits.EMAIL_BODY)
    @Builder.Default
    private String body = "";

    /** The sender person: an internal user or a customer login, whoever a From role resolved to. */
    @Column(name = "from_user_id")
    private Long fromUserId;

    /** Set when From was a role rather than a named person. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_role", length = 30)
    private EmailRole fromRole;

    /**
     * Which POC that role meant (L1). Null on rows written before levels existed, which meant the
     * customer's book, and that is how they are read back (L7).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_role_level", length = 10)
    private RoleLevel fromRoleLevel;

    @Column(name = "from_name", nullable = false, length = NAME_MAX)
    private String fromName;

    /** The person's own address (outbound) or the header address (inbound). */
    @Column(name = "from_address", length = ADDRESS_MAX)
    private String fromAddress;

    @Column(name = "from_customer_id")
    private Long fromCustomerId;

    @Column(name = "from_internal", nullable = false)
    private boolean fromInternal;

    /** The Gmail address the copies went out from: the sender's connected Gmail (M4). */
    @Column(name = "delivered_from", length = ADDRESS_MAX)
    private String deliveredFrom;

    /** Who pressed Send; null for received mail. */
    @Column(name = "sent_by_user_id")
    private Long sentByUserId;

    /** Comma-separated tokens that resolved to nobody, e.g. {@code ROLE:CUSTOMER:COLLECTION_POC,CUSTOMER}. */
    @Column(length = UNRESOLVED_MAX)
    private String unresolved;

    @Column(length = ERROR_MAX)
    private String error;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /**
     * When the mail service accepted the copies. From then on the service sends them and the email's
     * status follows theirs; until then the dispatcher and its sweeper own it.
     */
    @Column(name = "handed_off_at")
    private Instant handedOffAt;

    /**
     * Before the mail service: an attempt may have delivered the message without the app hearing so.
     * Kept for older rows; the service now looks after this per copy, so new email leaves it false.
     * The default fills existing rows when the column is added.
     */
    @Column(name = "delivery_uncertain", nullable = false)
    @ColumnDefault("false")
    private boolean deliveryUncertain;

    /** Received mail, and outbound mail sent before the mail service; the copies carry their own. */
    @Column(name = "provider_message_id", unique = true, length = 100)
    private String providerMessageId;

    @Column(name = "provider_thread_id", length = 100)
    private String providerThreadId;

    @Column(name = "rfc_message_id", length = HEADER_ID_MAX)
    private String rfcMessageId;

    @Column(name = "in_reply_to", length = HEADER_ID_MAX)
    private String inReplyTo;

    /** One id per bulk request, so a batch's emails can be found together. */
    @Column(name = "batch_id", length = 40)
    private String batchId;

    /** Outbound: when saved; inbound: when received. Newest first is {@code occurred_at desc, id desc}. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Also stamped by the dispatcher's bulk updates, which bypass {@link #onUpdate}. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.occurredAt == null) {
            this.occurredAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Whether the record this email hangs off has since been deleted; null reads as "no". */
    public boolean entityIsDeleted() {
        return Boolean.TRUE.equals(entityDeleted);
    }
}
