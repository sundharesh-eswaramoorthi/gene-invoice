package com.geneinvoice.mail.tracking;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A message a sync has dealt with in one mailbox, and how. It keeps a message from being handled
 * twice: a run that stopped reads the same history again, and passes these over without a download.
 */
@Entity
@Table(name = "mail_inbound", uniqueConstraints = @UniqueConstraint(name = "uk_mail_inbound_message",
        columnNames = {"connection_id", "provider_message_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailInbound {

    public enum Kind {
        /** Sent to the backend as a reply to a copy. */
        REPLY,
        /** A delivery failure: the copy it names bounced. */
        BOUNCE,
        /** A delay notice: nothing changes. */
        DELAY,
        /** Looked at and left: a report naming no copy, or one that is neither a failure nor a delay. */
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "provider_message_id", nullable = false, length = 100)
    private String providerMessageId;

    @Column(name = "provider_thread_id", length = 100)
    private String providerThreadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** The copy it concerned. */
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
