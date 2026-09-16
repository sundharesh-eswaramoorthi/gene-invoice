package com.geneinvoice.email;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An email kept inside the app. Nothing is delivered to a real mailbox: the sender, the To line and
 * the text are stored as they were at send time, and staff recipients read it in their Inbox.
 */
@Entity
@Table(name = "emails", indexes = {
        @Index(name = "idx_email_customer", columnList = "customer_id"),
        @Index(name = "idx_email_invoice", columnList = "invoice_id")
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

    /** Always set. For an invoice's email it is the invoice's customer, whose Email tab lists both. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** Set when the email is about an invoice rather than the customer as a whole. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    /** USER or ROLE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", nullable = false, length = 20)
    private EmailPartyType fromType;

    @Column(name = "from_user_id")
    private Long fromUserId;

    @Column(name = "from_role_id")
    private Long fromRoleId;

    /** The user's or role's name when the email was sent. */
    @Column(name = "from_name", nullable = false, length = 120)
    private String fromName;

    /** The user's or role's email address when the email was sent; null when it had none. */
    @Column(name = "from_address", length = 120)
    private String fromAddress;

    @Column(nullable = false, length = FieldLimits.EMAIL_SUBJECT)
    private String subject;

    @Column(nullable = false, length = FieldLimits.EMAIL_BODY)
    @Builder.Default
    private String body = "";

    /** The signed-in user who sent it, whoever it is from. */
    @Column(name = "sent_by_user_id", nullable = false)
    private Long sentByUserId;

    @Column(name = "sent_by_name", nullable = false, length = 120)
    private String sentByName;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /** The To line in the order it was written: users, then roles, then customer addresses. */
    @OneToMany(mappedBy = "email", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc")
    @BatchSize(size = 50)
    @Builder.Default
    private List<EmailRecipient> recipients = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }
}
