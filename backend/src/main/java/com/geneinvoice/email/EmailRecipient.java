package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * One person an email went to, resolved and stored when it was sent (E4). A row with a user id and
 * field {@code TO} is that user's Inbox entry, so someone who takes over a role later never sees
 * the emails that went before. An outbound To recipient with an address also gets a copy of their
 * own from the mail service (M6), whose delivery is tracked here.
 * <p>
 * Two writers change a row without locking each other out: the recipient marking it read in their
 * Inbox, and the mail service's reports on the copy (under the email's lock, which the Inbox does not
 * take). Each update therefore writes only the columns it changed, or one would put back what the
 * other had just written.
 */
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

    /** One row per person per email; someone in both To and Cc is To. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private RecipientField field;

    /** Set for internal users and customer logins; puts the email in their Inbox. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false, length = Email.NAME_MAX)
    private String name;

    /** Null for a person without an address, who still gets the in-app copy. */
    @Column(length = Email.ADDRESS_MAX)
    private String address;

    @Column(nullable = false)
    private boolean internal;

    /**
     * How the person was added, comma-separated in order: USER, ROLE:&lt;level&gt;:&lt;role&gt;,
     * CUSTOMER, MAILBOX, HEADER. A role written before levels existed is {@code ROLE:<role>} and
     * reads as customer level (L7). Someone reached at both levels keeps both (L6).
     */
    @Column(nullable = false, length = SOURCES_MAX)
    private String sources;

    /** Read in the app's Inbox. */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    // ---- the copy the mail service sends this recipient ------------------------------
    // All null, false or 0 for received mail, for people without an address, and for email sent
    // before the mail service. The defaults let ddl-auto add the columns to a table that has rows.

    /** Set to QUEUED when an outbound email is saved, for each To recipient with an address. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 12)
    private RecipientDeliveryStatus deliveryStatus;

    @Column(name = "delivery_error", length = Email.ERROR_MAX)
    private String deliveryError;

    /** The last {@code seq} applied, so an older report from the mail service never overwrites a newer one. */
    @Column(name = "delivery_seq", nullable = false)
    @ColumnDefault("0")
    private long deliverySeq;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** Read in the recipient's Gmail; {@link #readAt} is the app's Inbox. */
    @Column(name = "mail_read_at")
    private Instant mailReadAt;

    @Column(name = "bounced_at")
    private Instant bouncedAt;

    /** Seen in the recipient's own Gmail, rather than assumed from no bounce coming back. */
    @Column(name = "delivered_confirmed", nullable = false)
    @ColumnDefault("false")
    private boolean deliveredConfirmed;

    /** The copy's sender-side Gmail message id. */
    @Column(name = "provider_message_id", length = PROVIDER_ID_MAX)
    private String providerMessageId;

    /** The copy's sender-side Gmail thread id, which a reply shares. */
    @Column(name = "provider_thread_id", length = PROVIDER_ID_MAX)
    private String providerThreadId;

    @Column(name = "rfc_message_id", length = Email.HEADER_ID_MAX)
    private String rfcMessageId;
}
