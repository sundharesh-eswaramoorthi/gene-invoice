package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An email in one user's Inbox. There is one per person however they were addressed — named
 * directly, through a role, or both — and it carries that person's own read status.
 */
@Entity
@Table(name = "email_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_delivery_user", columnNames = {"email_id", "user_id"}),
        indexes = @Index(name = "idx_email_delivery_user", columnList = "user_id,read_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id")
    private Email email;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** When this user first opened or marked it read; null while unread. */
    @Column(name = "read_at")
    private Instant readAt;
}
