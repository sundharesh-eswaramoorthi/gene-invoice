package com.geneinvoice.automation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * The outbox's queries, in the {@code EmailRepository} shape they were transliterated from: a
 * paged id query to find work, a CONDITIONAL update to claim it, and a conditional update to
 * settle it (A5).
 *
 * <p>Nothing here is a check-then-act. {@link #claim} returns 1 for the instance that won the row
 * and 0 for the one that lost it, which is the cross-instance lock this repository otherwise
 * lacks — there is no leader election anywhere in this application and this design needs none.
 */
public interface AutomationEventRepository extends JpaRepository<AutomationEvent, Long> {

    /**
     * Due work, oldest first.
     *
     * <p>{@code createdAt < :createdBefore} is the SETTLE window and it is load-bearing, not a
     * tidiness: the event row is inserted in {@code beforeCommit} and the nudge happens in
     * {@code afterCompletion}, so for a moment a committed-looking row exists that the publishing
     * thread is still about to nudge. A sweeper that picked it up inside that window would race
     * the request thread for the same event. EmailRepository.findDue carries the identical clause
     * for the identical reason (A5).
     */
    @Query("""
            select e.id from AutomationEvent e
             where (e.status = com.geneinvoice.automation.EventStatus.NEW
                    or e.status = com.geneinvoice.automation.EventStatus.FAILED)
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
               and e.createdAt < :createdBefore
             order by e.id
            """)
    List<Long> findDue(@Param("now") Instant now,
                       @Param("createdBefore") Instant createdBefore,
                       Pageable page);

    /** 1 = this instance owns this fan-out; 0 = somebody else already has it (A5). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e
               set e.status = com.geneinvoice.automation.EventStatus.FANNING,
                   e.attempts = e.attempts + 1,
                   e.updatedAt = :now
             where e.id = :id
               and (e.status = com.geneinvoice.automation.EventStatus.NEW
                    or e.status = com.geneinvoice.automation.EventStatus.FAILED)
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);

    /**
     * The other half of the claim, and conditional for the same reason: only the instance that
     * still holds the row may finish it. A worker whose event was reclaimed by the sweeper after
     * a stall gets 0 back and rolls its own transaction away (A5).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationEvent e
               set e.status = :status,
                   e.lastError = :lastError,
                   e.nextAttemptAt = :nextAttemptAt,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = com.geneinvoice.automation.EventStatus.FANNING
            """)
    int settle(@Param("id") Long id,
               @Param("status") EventStatus status,
               @Param("lastError") String lastError,
               @Param("nextAttemptAt") Instant nextAttemptAt,
               @Param("now") Instant now);

    /**
     * The ids retention is allowed to remove, paged so a year of events is deleted in chunks
     * rather than in one statement that locks the table (A5).
     */
    @Query("""
            select e.id from AutomationEvent e
             where e.status = :status
               and e.createdAt < :createdBefore
             order by e.id
            """)
    List<Long> findDelivered(@Param("status") EventStatus status,
                             @Param("createdBefore") Instant createdBefore,
                             Pageable page);

    /**
     * One retention chunk, returning how many rows went.
     *
     * <p>A default method and not a {@code @Modifying delete}, because Spring Data cannot page a
     * bulk delete and an unpaged one is the statement this is trying to avoid. Steps and runs are
     * the audit trail; the events are not, which is why these can go at all (A5).
     */
    default int deleteDelivered(EventStatus status, Instant createdBefore, Pageable page) {
        List<Long> ids = findDelivered(status, createdBefore, page);
        if (!ids.isEmpty()) deleteAllByIdInBatch(ids);
        return ids.size();
    }
}
