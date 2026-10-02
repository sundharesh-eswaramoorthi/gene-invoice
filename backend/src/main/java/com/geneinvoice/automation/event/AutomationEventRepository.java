package com.geneinvoice.automation.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The outbox. Saves only insert into it, through {@link AutomationEvents}; they never read it (§5.8.3). The
 * consumer claims and finishes events with conditional updates (§5.10.6): a claim bumps {@code claims}, which is
 * the claim's fencing token, and every finish names the token it holds, so a worker whose lease ran out and was
 * claimed again by another can change nothing.
 */
public interface AutomationEventRepository extends JpaRepository<AutomationEvent, Long> {

    @Transactional(readOnly = true)
    List<AutomationEvent> findByHistoryTxIdOrderByIdAsc(String historyTxId);

    /** Events due now, oldest first. */
    @Query("""
            select e.id from AutomationEvent e
             where e.status = 'PENDING' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
             order by e.id
            """)
    List<Long> findDueIds(@Param("now") Instant now, Pageable page);

    /** Events whose worker's lease ran out: it stopped (a crash) or is too slow. */
    @Query("select e.id from AutomationEvent e where e.status = 'PROCESSING' and e.leaseUntil < :now order by e.id")
    List<Long> findExpiredLeaseIds(@Param("now") Instant now, Pageable page);

    /** Puts an event whose lease ran out back in the queue; that counts no attempt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PENDING', e.claimedBy = null, e.leaseUntil = null,
                   e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.leaseUntil < :now
            """)
    int recover(@Param("id") Long id, @Param("now") Instant now);

    /** Takes a due event for one worker; 0 rows when another has it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PROCESSING', e.claims = e.claims + 1, e.claimedBy = :node,
                   e.leaseUntil = :leaseUntil, e.updatedAt = :now
             where e.id = :id and e.status = 'PENDING' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            """)
    int claim(@Param("id") Long id, @Param("node") String node, @Param("now") Instant now,
              @Param("leaseUntil") Instant leaseUntil);

    @Query("select e.claims from AutomationEvent e where e.id = :id")
    int claimsOf(@Param("id") Long id);

    /** The claims since the event was last requeued: what the claim limit counts. */
    @Query("select e.claims - coalesce(e.claimsBase, 0) from AutomationEvent e where e.id = :id")
    int claimsSinceRequeue(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'DONE', e.processedAt = :now, e.leaseUntil = null,
                   e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int finishDone(@Param("id") Long id, @Param("token") int token, @Param("now") Instant now);

    /** Another attempt later; the error is kept (fitted by the caller). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PENDING', e.attempts = e.attempts + 1, e.nextAttemptAt = :next,
                   e.lastError = :error, e.claimedBy = null, e.leaseUntil = null, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int finishRetry(@Param("id") Long id, @Param("token") int token, @Param("now") Instant now,
                    @Param("next") Instant next, @Param("error") String error);

    /** Given up: kept for an administrator to requeue. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'DEAD', e.attempts = :attempts, e.lastError = :error,
                   e.processedAt = :now, e.leaseUntil = null, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int finishDead(@Param("id") Long id, @Param("token") int token, @Param("now") Instant now,
                   @Param("attempts") int attempts, @Param("error") String error);

    /** Handed back untried because the database went away: no attempt is counted. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PENDING', e.nextAttemptAt = :next, e.claimedBy = null,
                   e.leaseUntil = null, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int release(@Param("id") Long id, @Param("token") int token, @Param("now") Instant now,
                @Param("next") Instant next);

    /** Keeps the lease of an event still being worked on (a long set run). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.leaseUntil = :leaseUntil, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int heartbeat(@Param("id") Long id, @Param("token") int token, @Param("now") Instant now,
                  @Param("leaseUntil") Instant leaseUntil);

    /**
     * A dead event back in the queue, as new: requeued by an administrator, or retried from its failed run. Its
     * attempts start again, but {@code claims} is kept, so the next claim's token is one no earlier worker holds; the
     * claim limit counts from {@code claimsBase}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PENDING', e.attempts = 0, e.claimsBase = e.claims,
                   e.nextAttemptAt = null,
                   e.claimedBy = null, e.leaseUntil = null, e.processedAt = null, e.updatedAt = :now
             where e.id = :id and e.status = 'DEAD'
            """)
    int requeue(@Param("id") Long id, @Param("now") Instant now);

    /** "Run now" requests of a rule still waiting or running: an identical request is answered with its run. */
    @Transactional(readOnly = true)
    @Query("""
            select e from AutomationEvent e
             where e.ruleId = :ruleId and e.kind = 'MANUAL' and e.status in ('PENDING', 'PROCESSING')
             order by e.id
            """)
    List<AutomationEvent> findQueuedManual(@Param("ruleId") Long ruleId);

    /** Changes to records of a kind in {@code [from, to)} that a rule would have been asked about. */
    @Query("""
            select count(e) from AutomationEvent e
             where e.occurredAt >= :from and e.occurredAt < :to and e.ruleId is null
               and ((e.kind in :kinds and e.entityType = :entityType) or (:children = true and e.kind = 'CHILDREN'))
            """)
    long countEntityEvents(@Param("entityType") String entityType, @Param("kinds") Collection<String> kinds,
                           @Param("children") boolean children, @Param("from") Instant from,
                           @Param("to") Instant to);

    /**
     * The moment of the earliest change of these kinds the consumer has not finished with: what a rule suspended now
     * will not be asked about either. Null when there is none.
     */
    @Query("""
            select min(e.occurredAt) from AutomationEvent e
             where e.status in ('PENDING', 'PROCESSING') and e.ruleId is null
               and ((e.kind in :kinds and e.entityType = :entityType) or (:children = true and e.kind = 'CHILDREN'))
            """)
    Instant earliestUnprocessed(@Param("entityType") String entityType, @Param("kinds") Collection<String> kinds,
                                @Param("children") boolean children);

    /**
     * A set run's progress (§5.10.6): the last record whose actions are all settled, in the transaction that saves
     * the run's counters, so a run picked up again carries on from there. Progress gives the attempts back: a long
     * run is given up on when one page keeps failing, not for a few passing failures over many pages. Fenced by the
     * token.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.cursorId = :cursor, e.attempts = 0, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int checkpoint(@Param("id") Long id, @Param("token") int token, @Param("cursor") Long cursor,
                   @Param("now") Instant now);

    /**
     * A long set run hands its event back part way (its slice of time is used up), to be claimed again at once and
     * carried on from {@code cursor}. A slice finished is not a claim that never finished: the claim limit counts
     * afresh from here, while the token keeps growing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e set e.status = 'PENDING', e.cursorId = :cursor, e.nextAttemptAt = :now,
                   e.claimsBase = e.claims, e.claimedBy = null, e.leaseUntil = null, e.updatedAt = :now
             where e.id = :id and e.status = 'PROCESSING' and e.claims = :token
            """)
    int yieldAt(@Param("id") Long id, @Param("token") int token, @Param("cursor") Long cursor,
                @Param("now") Instant now);

    /**
     * The changes a resumed rule replays (§5.10.7): processed changes to records of its kind (and a customer's
     * CHILDREN events for invoice and payment rules) made in {@code [from, to)}, after {@code afterId}, oldest first.
     */
    @Transactional(readOnly = true)
    @Query("""
            select e from AutomationEvent e
             where e.status = 'DONE' and e.ruleId is null and e.id > :afterId
               and e.occurredAt >= :from and e.occurredAt < :to
               and ((e.kind in :kinds and e.entityType = :entityType) or (:children = true and e.kind = 'CHILDREN'))
             order by e.id
            """)
    List<AutomationEvent> findReplayable(@Param("entityType") String entityType,
                                         @Param("kinds") Collection<String> kinds,
                                         @Param("children") boolean children, @Param("from") Instant from,
                                         @Param("to") Instant to, @Param("afterId") Long afterId, Pageable page);

    /** Whether a customer's change was already handed to this rule as an event of its own (the live fan-out). */
    @Query("""
            select count(e) > 0 from AutomationEvent e
             where e.ruleId = :ruleId and e.kind = 'CHILDREN' and e.entityId = :entityId
               and e.historyTxId = :historyTxId
            """)
    boolean fannedOutTo(@Param("ruleId") Long ruleId, @Param("entityId") Long entityId,
                        @Param("historyTxId") String historyTxId);

    /** Processed events old enough to go (§5.10.8); the ones given up on are kept for an administrator. */
    @Query("select e.id from AutomationEvent e where e.status = 'DONE' and e.processedAt < :before order by e.id")
    List<Long> findPurgeableIds(@Param("before") Instant before, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AutomationEvent e where e.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);

    // ---- health (§6.14) ------------------------------------------------------------------------------------

    @Query("select count(e) from AutomationEvent e where e.status = :status and e.attempts = 0")
    long countFirstTries(@Param("status") String status);

    @Query("select count(e) from AutomationEvent e where e.status = :status and e.attempts > 0")
    long countRetries(@Param("status") String status);

    long countByStatus(String status);

    /** When the event waiting longest became due: how far behind the consumer is. */
    @Query("""
            select min(coalesce(e.nextAttemptAt, e.occurredAt)) from AutomationEvent e
             where e.status = 'PENDING' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            """)
    Instant oldestDue(@Param("now") Instant now);

    @Transactional(readOnly = true)
    List<AutomationEvent> findByStatusOrderByUpdatedAtDescIdDesc(String status, Pageable page);
}
