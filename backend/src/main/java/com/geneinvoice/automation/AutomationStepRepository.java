package com.geneinvoice.automation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The work queue's queries, in the EmailRepository shape they were transliterated from, with its
 * one bug fixed (A5).
 *
 * <p>THE FIX: {@link #claim} and {@link #settle} each take ONE {@code now} and use it for BOTH
 * the eligibility test and the timestamp. EmailDispatcher compares against a {@code due} it was
 * handed and then writes a fresh {@code Instant.now()}, so under a time seam the row it claimed
 * and the row it stamped disagree about when the claim happened (A5).
 *
 * <p>Both updates are CONDITIONAL, and settle additionally conditions on the CLAIM TOKEN. That is
 * the fence: a worker whose step was reclaimed by the sweeper after a stall gets 0 back and must
 * roll its own transaction away, taking the Task or the Email it had written with it (A5).
 */
public interface AutomationStepRepository extends JpaRepository<AutomationStep, Long> {

    /**
     * Due work, oldest first.
     *
     * <p>{@code createdAt < :createdBefore} is the SETTLE window, the identical clause and the
     * identical reason EmailRepository.findDue carries: a step is inserted by the fan-out and
     * worked by the thread that planned it, so a sweeper picking it up inside that window would
     * race its own planner for the same row (A5).
     */
    @Query("""
            select s.id from AutomationStep s
             where s.status = com.geneinvoice.automation.StepStatus.QUEUED
               and (s.nextAttemptAt is null or s.nextAttemptAt <= :now)
               and s.createdAt < :createdBefore
             order by s.id
            """)
    List<Long> findDue(@Param("now") Instant now,
                       @Param("createdBefore") Instant createdBefore,
                       Pageable page);

    /** A step whose worker died holding it: claimed long ago and still RUNNING (A5). */
    @Query("""
            select s.id from AutomationStep s
             where s.status = com.geneinvoice.automation.StepStatus.RUNNING
               and s.claimedAt is not null and s.claimedAt < :staleBefore
             order by s.id
            """)
    List<Long> findStaleRunning(@Param("staleBefore") Instant staleBefore, Pageable page);

    /**
     * 1 = this worker owns this step and its token is the fence; 0 = somebody else has it (A5).
     *
     * <p>TWO INSTANTS, AND THEY ARE NOT THE SAME CLOCK. {@code :now} is the sweep's own instant —
     * the eligibility test, shared with {@code findDue} so that what was selected is what is
     * claimed. {@code :claimedAt} is the LIVENESS timestamp, and it has to be read fresh: it is
     * the clock {@link #findStaleRunning} reads, so a sweep of 200 steps that takes longer than
     * STALE_RUNNING would otherwise stamp every step it claims with the instant the sweep began
     * and hand its own live workers to the next reclaimer — burning an attempt each time (A5).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationStep s
               set s.status = com.geneinvoice.automation.StepStatus.RUNNING,
                   s.attempts = s.attempts + 1,
                   s.claimToken = :token,
                   s.claimedAt = :claimedAt,
                   s.updatedAt = :claimedAt
             where s.id = :id
               and s.status = com.geneinvoice.automation.StepStatus.QUEUED
               and (s.nextAttemptAt is null or s.nextAttemptAt <= :now)
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now,
              @Param("claimedAt") Instant claimedAt, @Param("token") String token);

    /**
     * The other half of the fence. Conditional on the token as well as on RUNNING, so ONLY the
     * worker that still holds the step may finish it — and because the settle and the domain
     * write share one transaction, a 0 here rolls the Task, Promise, Dispute or Email away with
     * it. Committing the domain write in an inner transaction "for tidiness" would leave two (A5).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationStep s
               set s.status = :status,
                   s.result = :result,
                   s.producedType = :producedType,
                   s.producedId = :producedId,
                   s.unresolved = :unresolved,
                   s.finishedAt = :now,
                   s.updatedAt = :now,
                   s.nextAttemptAt = null
             where s.id = :id
               and s.status = com.geneinvoice.automation.StepStatus.RUNNING
               and s.claimToken = :token
            """)
    int settle(@Param("id") Long id,
               @Param("status") StepStatus status,
               @Param("result") String result,
               @Param("producedType") ProducedType producedType,
               @Param("producedId") Long producedId,
               @Param("unresolved") String unresolved,
               @Param("now") Instant now,
               @Param("token") String token);

    /**
     * Is there an EARLIER action of the same rule, on the same record, on the same occasion, that
     * has not finished? That is what makes "a later action waits for an earlier one" true, and
     * what makes "an earlier action that failed skips the later ones" expressible without a
     * second state machine (A3, A5).
     */
    @Query("""
            select count(s) > 0 from AutomationStep s
             where s.occasion = :occasion and s.ruleId = :ruleId
               and s.subjectId = :subjectId and s.actionIndex < :actionIndex
               and s.status in (com.geneinvoice.automation.StepStatus.QUEUED,
                                com.geneinvoice.automation.StepStatus.RUNNING)
            """)
    boolean existsEarlierUnfinished(@Param("occasion") String occasion,
                                    @Param("ruleId") Long ruleId,
                                    @Param("subjectId") Long subjectId,
                                    @Param("actionIndex") int actionIndex);

    /**
     * Has this run any work left? That is the whole test for "the run is finished", and it is a
     * question about the STEPS rather than about the run, because the run row knows only how many
     * it planned and never how many have landed (A5).
     */
    boolean existsByRunIdAndStatusIn(Long runId, Collection<StepStatus> statuses);

    /** The sidebar badge's number: steps nobody is going to retry (A5). */
    @Query("""
            select count(s) from AutomationStep s
             where s.status = com.geneinvoice.automation.StepStatus.POISONED
            """)
    long countPoisoned();

    /** The ids retention may remove: finished long enough ago to be nobody's history (A5). */
    @Query("""
            select s.id from AutomationStep s
             where s.status in (com.geneinvoice.automation.StepStatus.DONE,
                                com.geneinvoice.automation.StepStatus.SKIPPED)
               and s.finishedAt is not null and s.finishedAt < :finishedBefore
             order by s.id
            """)
    List<Long> findSettledBefore(@Param("finishedBefore") Instant finishedBefore, Pageable page);

    /**
     * One retention chunk, returning how many rows went. A default method and not a
     * {@code @Modifying delete}, because Spring Data cannot page a bulk delete and an unpaged one
     * is the table-locking statement this is trying to avoid. POISONED steps are deliberately NOT
     * deleted: a step nobody is going to retry is exactly the one somebody still has to read (A5).
     */
    default int deleteSettledBefore(Instant finishedBefore, Pageable page) {
        List<Long> ids = findSettledBefore(finishedBefore, page);
        if (!ids.isEmpty()) deleteAllByIdInBatch(ids);
        return ids.size();
    }
}
