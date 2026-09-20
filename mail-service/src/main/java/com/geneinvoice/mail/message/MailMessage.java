package com.geneinvoice.mail.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One copy of an email for one recipient (M6): its own To header, its own Message-ID and its own
 * status. The queue carries only the id; this row is the truth. {@code seq} goes up by one with
 * every change the client can see, so the backend can tell a newer state from an older one.
 */
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

    /** The client's key, e.g. {@code gi-91-501}: a copy handed over twice is the same copy. */
    @Column(name = "external_id", nullable = false, unique = true, length = EXTERNAL_ID_MAX)
    private String externalId;

    /** The client's email id, shared by the copies of one email. */
    @Column(name = "group_ref", length = GROUP_REF_MAX)
    private String groupRef;

    /** The sender's connection when submitted; null when there was none. */
    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "sender_ref", nullable = false, length = OWNER_REF_MAX)
    private String senderRef;

    @Column(name = "from_name", nullable = false, length = NAME_MAX)
    private String fromName;

    /** The Gmail address it went out from, once sent. */
    @Column(name = "from_address", length = ADDRESS_MAX)
    private String fromAddress;

    @Column(name = "to_name", nullable = false, length = NAME_MAX)
    private String toName;

    @Column(name = "to_address", nullable = false, length = ADDRESS_MAX)
    private String toAddress;

    /** {@code to_address} in lower case, to find the recipient's own connection. */
    @Column(name = "to_address_key", nullable = false, length = ADDRESS_MAX)
    private String toAddressKey;

    @Column(nullable = false, length = SUBJECT_MAX)
    private String subject;

    @Column(nullable = false, length = BODY_MAX)
    private String body;

    /** {@code <gm-uuid@domain>}, fixed at submit and kept across attempts, so a bounce or a reply finds the copy. */
    @Column(name = "rfc_message_id", nullable = false, length = HEADER_ID_MAX)
    private String rfcMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MessageStatus status;

    @Column(nullable = false)
    private long seq;

    /** Why it failed, was not sent, or bounced. */
    @Column(length = ERROR_MAX)
    private String error;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** When it was last put on the queue, or, for a retry, when it comes off the delay queue. */
    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    /** An attempt may have delivered it without the service hearing so: the next one looks in Sent first. */
    @Column(name = "delivery_uncertain", nullable = false)
    private boolean deliveryUncertain;

    /** The sender-side Gmail message id. */
    @Column(name = "provider_message_id", unique = true, length = 100)
    private String providerMessageId;

    /** The sender-side Gmail thread, where bounces and replies arrive. */
    @Column(name = "provider_thread_id", length = 100)
    private String providerThreadId;

    /** The recipient's own connection, once the copy was found in it. */
    @Column(name = "recipient_connection_id")
    private Long recipientConnectionId;

    /** The copy's id in the recipient's own mailbox, whose UNREAD label says whether it was read. */
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

    /** True when seen in the recipient's mailbox; false when delivery is only estimated. */
    @Column(name = "delivered_confirmed", nullable = false)
    private boolean deliveredConfirmed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
