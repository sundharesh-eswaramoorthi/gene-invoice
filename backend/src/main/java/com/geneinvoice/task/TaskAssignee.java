package com.geneinvoice.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One person's seat on one task. A join table rather than an @ElementCollection, so that "assigned
 * to me" is an EXISTS over an indexed table and my-work is an index scan (A6).
 *
 * <p>task_id is the ONE new foreign key in the whole programme, and it is allowed because both
 * tables are new: everywhere else a cross-aggregate reference is a raw Long with no association,
 * the Dispute.customerId house convention — which is why user_id here is a plain Long (A6).
 */
@Entity
@Table(name = "task_assignees",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_assignee",
                columnNames = {"task_id", "user_id"}),
        indexes = @Index(name = "idx_task_assignee_user", columnList = "user_id,task_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskAssignee {

    /** "USER", or a stored RoleRef token such as "ROLE:CUSTOMER:COLLECTION_POC" (A6, A3). */
    public static final int SOURCE_MAX = 60;

    /** Somebody picked this person by name. The only source a person's own request can produce. */
    public static final String SOURCE_USER = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // WHY this person is on it, so the detail screen can say "Collection POC (customer)" for a
    // seat a rule filled from a role rather than showing a bare name (A6, A3).
    @Column(nullable = false, length = SOURCE_MAX)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
