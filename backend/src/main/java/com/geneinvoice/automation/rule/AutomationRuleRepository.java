package com.geneinvoice.automation.rule;

import com.geneinvoice.automation.run.RunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AutomationRule r where r.id = :id")
    Optional<AutomationRule> findByIdForUpdate(@Param("id") Long id);

    /** Names are unique among the rules not deleted, whatever their case. */
    @Query("""
            select count(r) > 0 from AutomationRule r
             where lower(r.name) = lower(:name) and r.deletedAt is null and (:exceptId is null or r.id <> :exceptId)
            """)
    boolean nameTaken(@Param("name") String name, @Param("exceptId") Long exceptId);

    /** Rules still standing that this person owns: someone who owns one is deactivated, not deleted. */
    long countByOwnerUserIdAndDeletedAtIsNull(Long ownerUserId);

    /**
     * The rules a change to a record of this kind sets off (§5.10.6): on, standing, not suspended, triggered by these
     * kinds, and on since before the change happened ({@code active_since}), so a backlog is never theirs.
     */
    @Transactional(readOnly = true)
    @Query("""
            select r from AutomationRule r
             where r.entityType in :entities and r.triggerKind in :kinds and r.enabled = true
               and r.suspendedReason is null and r.deletedAt is null
               and r.activeSince is not null and r.activeSince <= :occurredAt
             order by r.id
            """)
    List<AutomationRule> findEntityCandidates(@Param("entities") Collection<TriggerEntity> entities,
                                              @Param("kinds") Collection<TriggerKind> kinds,
                                              @Param("occurredAt") Instant occurredAt);

    /**
     * Stops a rule acting on its own (§5.10.7); only the first reason counts. A JPQL update, so the version a user
     * is editing against does not move.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r set r.suspendedReason = :reason, r.suspendedDetail = :detail,
                   r.suspendedAt = :now, r.nextRunAt = null
             where r.id = :id and r.suspendedReason is null and r.deletedAt is null
            """)
    int suspend(@Param("id") Long id, @Param("reason") SuspendReason reason, @Param("detail") String detail,
                @Param("now") Instant now);

    /**
     * A suspended rule's changes go unasked from an earlier moment: a replay the suspension stopped part way leaves
     * the changes it did not reach to the next resume (§5.10.7). The version does not move.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r set r.suspendedAt = :at
             where r.id = :id and r.suspendedReason is not null and r.suspendedAt > :at
            """)
    int suspendedSince(@Param("id") Long id, @Param("at") Instant at);

    /** What the rule's latest run came to; not a user edit, so the version does not move. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AutomationRule r set r.lastRunId = :runId, r.lastRunAt = :at, r.lastRunStatus = :status where r.id = :id")
    int recordLastRun(@Param("id") Long id, @Param("runId") Long runId, @Param("at") Instant at,
                      @Param("status") RunStatus status);

    /** The rules still standing, and not suspended, that this person owns. */
    @Query("select r.id from AutomationRule r where r.ownerUserId = :owner and r.deletedAt is null and r.suspendedReason is null order by r.id")
    List<Long> findActiveIdsOwnedBy(@Param("owner") Long ownerUserId);

    /** The owners of standing rules who hold this role: whom a change to the role may leave unable to run them. */
    @Query("""
            select distinct r.ownerUserId from AutomationRule r
             where r.deletedAt is null and r.suspendedReason is null
               and r.ownerUserId in (select u.id from User u where u.role.id = :roleId)
            """)
    List<Long> findOwnersWithRole(@Param("roleId") Long roleId);

    /**
     * Scheduled rules whose next slot has come (§5.10.8): on, standing, not suspended, soonest first. Tick after tick
     * takes a page of them.
     */
    @Transactional(readOnly = true)
    @Query("""
            select r from AutomationRule r
             where r.triggerKind in (com.geneinvoice.automation.rule.TriggerKind.SCHEDULE_DAILY,
                                     com.geneinvoice.automation.rule.TriggerKind.SCHEDULE_WEEKLY)
               and r.enabled = true and r.suspendedReason is null and r.deletedAt is null
               and r.nextRunAt is not null and r.nextRunAt <= :now
             order by r.nextRunAt, r.id
            """)
    List<AutomationRule> findDueSchedules(@Param("now") Instant now, Pageable page);

    /**
     * Moves a scheduled rule on to its next slot, only if no other tick did first ({@code nextRunAt} is still what
     * this one read). A JPQL update: the engine's bookkeeping never moves the version a user is editing against.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r set r.nextRunAt = :next, r.lastScheduledFor = :lastSlot
             where r.id = :id and r.nextRunAt = :expected
            """)
    int advanceSchedule(@Param("id") Long id, @Param("expected") Instant expected, @Param("next") Instant next,
                        @Param("lastSlot") Instant lastSlot);
}
