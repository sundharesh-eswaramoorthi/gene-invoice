package com.geneinvoice.mail.tracking;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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
        REPLY,
        BOUNCE,
        DELAY,
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

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
