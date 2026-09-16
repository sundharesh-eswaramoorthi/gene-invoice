package com.geneinvoice.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/** One entry in an email's To line, as it stood when the email was sent. */
@Entity
@Table(name = "email_recipients", indexes = @Index(name = "idx_email_recipient_email", columnList = "email_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_id")
    private Email email;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailPartyType type;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "role_id")
    private Long roleId;

    /** The user's or role's name; null for a customer address. */
    @Column(length = 120)
    private String name;

    /** The user's, role's or customer's address; null for a user or role that had none. */
    @Column(length = 120)
    private String address;

    /**
     * For a role, the people in it when the email was sent. Someone who joins the role later is not
     * added here, and gets no copy of the email.
     */
    @ElementCollection
    @CollectionTable(name = "email_role_members", joinColumns = @JoinColumn(name = "recipient_id"))
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<EmailRoleMember> members = new ArrayList<>();
}
