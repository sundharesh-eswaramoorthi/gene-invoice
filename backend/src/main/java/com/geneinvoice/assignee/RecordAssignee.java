package com.geneinvoice.assignee;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One assignee of a task, a dispute or a promise: a person, or a role at a level, in the email token shape
 * (§3.7). Rows are never deleted: a removed assignee keeps its interval, which is how assignees are answered as of
 * a date (D8). Not versioned; no foreign keys.
 */
@Entity
@Table(name = "record_assignees", indexes = {
        @Index(name = "idx_record_assignee_owner", columnList = "owner_type,owner_id"),
        @Index(name = "idx_record_assignee_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordAssignee {

    public static final String USER = "USER";
    public static final String ROLE = "ROLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TASK, DISPUTE or PROMISE. */
    @Column(name = "owner_type", nullable = false, length = 10)
    private String ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** USER or ROLE. */
    @Column(nullable = false, length = 4)
    private String kind;

    @Column(name = "user_id")
    private Long userId;

    /** An {@code EmailRole} name. */
    @Column(length = 30)
    private String role;

    /** CUSTOMER or RECORD. */
    @Column(name = "role_level", length = 10)
    private String roleLevel;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "added_by_user_id")
    private Long addedByUserId;

    /** Null while the assignee is current. */
    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_by_user_id")
    private Long removedByUserId;
}
