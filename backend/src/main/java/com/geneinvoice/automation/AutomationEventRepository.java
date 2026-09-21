package com.geneinvoice.automation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AutomationEventRepository extends JpaRepository<AutomationEvent, Long> {

    /**
     * The row, locked until the transaction ends. A worker settling its own run and the sweeper
     * giving up on that same run as stale are two writers to one row, and without the lock the
     * later read wins whichever order they happen to arrive in.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AutomationEvent e where e.id = :id")
    Optional<AutomationEvent> findByIdForUpdate(@Param("id") Long id);

    /**
     * Says these rows have gone to the transport, in one statement rather than one read and one
     * write each: a sweep republishing a backlog should not cost two queries per row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AutomationEvent e set e.enqueuedAt = :at where e.id in :ids")
    int markEnqueued(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    /**
     * Which of these keys the table already holds, in one read. The unique index is what actually
     * stops duplicate work (R4); this only spares the common case the cost of finding that out one
     * failed insert at a time.
     */
    @Query("select e.idempotencyKey from AutomationEvent e where e.idempotencyKey in :keys")
    List<String> findExistingKeys(@Param("keys") Collection<String> keys);

    /** What one rule has done lately, newest first, for the runs list. */
    List<AutomationEvent> findByRuleIdOrderByIdDesc(Long ruleId, Pageable page);

    /**
     * Takes a queued row for a worker. Only one caller can win it, so a nudge and the sweeper
     * reaching the same row never both run it, and nobody takes a retry before its wait is over as
     * of {@code due}.
     *
     * <p>{@code clearAutomatically} and {@code flushAutomatically} are not decoration. Without the
     * flush, a change the caller has made and not flushed is written after this update and can
     * overwrite the claim; without the clear, the persistence context goes on serving the row as it
     * was, so the worker reads status QUEUED, believes it has fresh work, and the claim it just won
     * counts for nothing. Both are silent — the code looks right and runs the action twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e
               set e.status = com.geneinvoice.automation.AutomationEventStatus.RUNNING,
                   e.attempts = e.attempts + 1,
                   e.updatedAt = :now
             where e.id = :id and e.status = com.geneinvoice.automation.AutomationEventStatus.QUEUED
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :due)
            """)
    int claim(@Param("id") Long id, @Param("due") Instant due, @Param("now") Instant now);

    /** Everything queued whose wait is over, oldest first: the catch-up after the service was down. */
    @Query("""
            select e.id from AutomationEvent e
             where e.status = com.geneinvoice.automation.AutomationEventStatus.QUEUED
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
             order by e.id
            """)
    List<Long> findDue(@Param("now") Instant now, Pageable page);

    /**
     * Queued rows that are due and that nobody has handed to the transport recently, so either the
     * nudge was never sent, or it was and the process died holding it. The {@code enqueuedBefore}
     * window is what keeps the sweeper off rows a nudge is still carrying.
     */
    @Query("""
            select e.id from AutomationEvent e
             where e.status = com.geneinvoice.automation.AutomationEventStatus.QUEUED
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
               and (e.enqueuedAt is null or e.enqueuedAt < :enqueuedBefore)
             order by e.id
            """)
    List<Long> findUnpublished(@Param("now") Instant now, @Param("enqueuedBefore") Instant enqueuedBefore,
                               Pageable page);

    /**
     * Rows claimed by a worker that nothing has touched since {@code touchedBefore}: the worker
     * died half way through. They are NOT run again — the action may already have happened, and
     * creating a second task is worse than leaving the first one unrecorded — so the sweeper marks
     * them failed with the reason and a person can press Run now (R8).
     */
    @Query("""
            select e.id from AutomationEvent e
             where e.status = com.geneinvoice.automation.AutomationEventStatus.RUNNING
               and e.updatedAt < :touchedBefore
             order by e.id
            """)
    List<Long> findStaleRunning(@Param("touchedBefore") Instant touchedBefore, Pageable page);

    /**
     * Drops settled rows past their keep-for. Only DONE and SKIPPED go: a FAILED row is the only
     * record that something may not have happened, and nobody should have to find that out from a
     * log. Clears the persistence context, which would otherwise keep serving the rows just deleted.
     *
     * <p>Note what this un-does: a CREATED row's key never changes, so deleting it lets the same
     * record fire that rule again. That is why the keep-for is days and not hours — long past any
     * sweep or retry — and why Run now is the supported way to make a rule act twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from AutomationEvent e
             where e.status in (com.geneinvoice.automation.AutomationEventStatus.DONE,
                                com.geneinvoice.automation.AutomationEventStatus.SKIPPED)
               and e.updatedAt < :before
            """)
    int deleteSettledBefore(@Param("before") Instant before);
}
