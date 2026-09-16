package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;

/**
 * One immutable recipient occurrence of an Email, captured at send time. A user reached through
 * two selected roles appears once under each role (EDGE2), so this row is never the unit of Inbox
 * state — {@link EmailRecipientRead} is.
 */
@Entity
@Table(name = "email_recipients", indexes = {
        @Index(name = "idx_email_recipient_email", columnList = "email_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmailRecipientKind kind;

    /** Display name at send time: the user's name, or the Customer address itself. */
    @Column(nullable = false, length = 160)
    private String label;

    @Column(length = 120)
    private String address;

    /** The selected role this occurrence belongs to; only set for ROLE occurrences (AC11). */
    @Column(name = "role_name", length = 80)
    private String roleName;

    /** The internal user this occurrence resolves to; null for Customer addresses (FR18). */
    @Column(name = "user_id")
    private Long userId;
}
