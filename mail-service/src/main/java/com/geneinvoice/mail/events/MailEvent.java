package com.geneinvoice.mail.events;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One change the backend hears about (§4.8), written in the transaction of the change itself, so an
 * event exists exactly when its change does. The id is the delivery order.
 */
@Entity
@Table(name = "mail_events", indexes = @Index(name = "idx_mail_event_delivery", columnList = "delivered_at,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailEvent {

    public static final int ERROR_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "owner_ref", length = 64)
    private String ownerRef;

    @Column(name = "external_id", length = 100)
    private String externalId;

    /** The event's {@code data}, as JSON. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;
}
