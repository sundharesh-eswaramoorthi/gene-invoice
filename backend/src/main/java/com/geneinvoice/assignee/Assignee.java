package com.geneinvoice.assignee;

import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One assignee of a task, promise or dispute (A1). A row is either a person ({@code userId}) or a
 * role at a level ({@code role} + {@code level}), never both and never neither — the same two
 * shapes the email To field already offers, so one picker answers for all of them.
 *
 * <p>A role row stores the seat, not the people in it: who it reaches is read from the customer's
 * POC book, or from the record's own POC field, at the moment it is asked for (A2). Somebody who
 * joins the book later is assigned by the same row; somebody who leaves stops being assigned. This
 * is why {@code customerId} is denormalised here — the book is read by customer, and a list of
 * "my tasks" must reach it without joining back through the parent record.
 */
@Entity
@Table(name = "assignees", indexes = {
        @Index(name = "idx_assignee_owner", columnList = "owner_type,owner_id"),
        @Index(name = "idx_assignee_user", columnList = "user_id"),
        @Index(name = "idx_assignee_customer", columnList = "customer_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private AssigneeOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AssigneeKind kind;

    /** The person, for a USER row; null for a ROLE row. */
    @Column(name = "user_id")
    private Long userId;

    /** The seat, for a ROLE row; null for a USER row. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private EmailRole role;

    /** Which POC the seat means — the customer's book, or the record's own field; null for a USER row. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RoleLevel level;

    /**
     * The customer the parent record belongs to, copied here so a role row can be matched against
     * the POC book without joining back through the parent. Null only where the parent has no
     * customer, which no kind of owner has today.
     */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
