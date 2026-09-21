package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Entity
@DynamicUpdate
@Table(name = "email_recipients", indexes = {
        @Index(name = "idx_email_recipient_inbox", columnList = "user_id,field,is_read"),
        @Index(name = "idx_email_recipient_email", columnList = "email_id"),
        @Index(name = "idx_email_recipient_customer", columnList = "customer_id"),
        @Index(name = "idx_email_recipient_thread", columnList = "provider_thread_id"),
        @Index(name = "idx_email_recipient_rfc_message", columnList = "rfc_message_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRecipient {

    public static final int SOURCES_MAX = 300;
    public static final int PROVIDER_ID_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id")
    private Email email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private RecipientField field;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false, length = Email.NAME_MAX)
    private String name;

    @Column(length = Email.ADDRESS_MAX)
    private String address;

    @Column(nullable = false)
    private boolean internal;

    @Column(nullable = false, length = SOURCES_MAX)
    private String sources;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 12)
    private RecipientDeliveryStatus deliveryStatus;

    @Column(name = "delivery_error", length = Email.ERROR_MAX)
    private String deliveryError;

    @Column(name = "delivery_seq", nullable = false)
    @ColumnDefault("0")
    private long deliverySeq;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "mail_read_at")
    private Instant mailReadAt;

    @Column(name = "bounced_at")
    private Instant bouncedAt;

    @Column(name = "delivered_confirmed", nullable = false)
    @ColumnDefault("false")
    private boolean deliveredConfirmed;

    @Column(name = "provider_message_id", length = PROVIDER_ID_MAX)
    private String providerMessageId;

    @Column(name = "provider_thread_id", length = PROVIDER_ID_MAX)
    private String providerThreadId;

    @Column(name = "rfc_message_id", length = Email.HEADER_ID_MAX)
    private String rfcMessageId;
}
