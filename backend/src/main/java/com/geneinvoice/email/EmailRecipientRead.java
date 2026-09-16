package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;

/**
 * One internal user's Inbox state for one Email: the only mutable Email-related row, always
 * created unread and owned by exactly one user, so one recipient's read never changes another
 * recipient's state (FR15). Customer-address recipients have no such row (FR18).
 */
@Entity
@Table(name = "email_recipient_reads", indexes = {
        @Index(name = "idx_email_read_user", columnList = "user_id,is_read"),
        @Index(name = "idx_email_read_email", columnList = "email_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRecipientRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;
}
