package com.geneinvoice.mail.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "mail_messages", indexes = {
        @Index(name = "idx_mail_message_due", columnList = "status,next_attempt_at"),
        @Index(name = "idx_mail_message_thread", columnList = "connection_id,provider_thread_id"),
        @Index(name = "idx_mail_message_recipient_msg", columnList = "recipient_message_id"),
        @Index(name = "idx_mail_message_to", columnList = "to_address_key"),
        @Index(name = "idx_mail_message_rfc", columnList = "rfc_message_id"),
        @Index(name = "idx_mail_message_group", columnList = "group_ref")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailMessage {

    public static final int EXTERNAL_ID_MAX = 100;
    public static final int GROUP_REF_MAX = 100;
    public static final int OWNER_REF_MAX = 64;
    public static final int NAME_MAX = 200;
    public static final int ADDRESS_MAX = 320;
    public static final int SUBJECT_MAX = 500;
    public static final int BODY_MAX = 20000;
    public static final int HEADER_ID_MAX = 300;
    public static final int ERROR_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = EXTERNAL_ID_MAX)
    private String externalId;

    @Column(name = "group_ref", length = GROUP_REF_MAX)
    private String groupRef;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "sender_ref", nullable = false, length = OWNER_REF_MAX)
    private String senderRef;

    @Column(name = "from_name", nullable = false, length = NAME_MAX)
    private String fromName;

    @Column(name = "from_address", length = ADDRESS_MAX)
    private String fromAddress;

    @Column(name = "to_name", nullable = false, length = NAME_MAX)
    private String toName;

    @Column(name = "to_address", nullable = false, length = ADDRESS_MAX)
    private String toAddress;

    @Column(name = "to_address_key", nullable = false, length = ADDRESS_MAX)
    private String toAddressKey;

    @Column(nullable = false, length = SUBJECT_MAX)
    private String subject;

    @Column(nullable = false, length = BODY_MAX)
    private String body;

    @Column(name = "rfc_message_id", nullable = false, length = HEADER_ID_MAX)
    private String rfcMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MessageStatus status;

    @Column(nullable = false)
    private long seq;

    @Column(length = ERROR_MAX)
    private String error;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    @Column(name = "delivery_uncertain", nullable = false)
    private boolean deliveryUncertain;

    @Column(name = "provider_message_id", unique = true, length = 100)
    private String providerMessageId;

    @Column(name = "provider_thread_id", length = 100)
    private String providerThreadId;

    @Column(name = "recipient_connection_id")
    private Long recipientConnectionId;

    @Column(name = "recipient_message_id", length = 100)
    private String recipientMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "bounced_at")
    private Instant bouncedAt;

    @Column(name = "delivered_confirmed", nullable = false)
    private boolean deliveredConfirmed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
