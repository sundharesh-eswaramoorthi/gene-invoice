package com.geneinvoice.email;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One stored in-app Email. It is a record, not a message: creating it persists this row with a
 * sender snapshot, recipient snapshots and per-recipient read rows, and nothing is delivered to
 * a real mailbox (FR1). Exactly one of {@code customer} / {@code invoice} is set.
 */
@Entity
@Table(name = "emails", indexes = {
        @Index(name = "idx_email_customer", columnList = "customer_id"),
        @Index(name = "idx_email_invoice", columnList = "invoice_id"),
        @Index(name = "idx_email_sent", columnList = "sent_at,id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who the Email was sent from, as selected: the user's name or the role's name. */
    @Column(name = "sender_display", nullable = false, length = 160)
    private String senderDisplay;

    /** The address resolved at send time: the role's address, the user's address, or the
     *  application-wide admin fallback when neither has one. */
    @Column(name = "sender_address", length = FieldLimits.EMAIL)
    private String senderAddress;

    @Column(nullable = false, length = FieldLimits.EMAIL_SUBJECT)
    private String subject;

    @Column(length = FieldLimits.EMAIL_BODY)
    private String body;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "sent_by_user_id", updatable = false)
    private Long sentByUserId;

    @Column(name = "sent_by_display", length = 160, updatable = false)
    private String sentByDisplay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @PrePersist
    void onCreate() {
        this.sentAt = Instant.now();
    }
}
