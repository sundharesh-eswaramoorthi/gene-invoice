package com.geneinvoice.automation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The run's queries, complete on day one so A-SCHEDULE reopens none of them (A5). */
public interface AutomationRunRepository extends JpaRepository<AutomationRun, Long> {

    /**
     * The row lock the keyset cursor rides on: one page is materialised and the cursor advanced in
     * ONE transaction, and two sweepers must not both advance it. PESSIMISTIC_WRITE and not an
     * optimistic version, because the loser here should WAIT and take the next page rather than
     * throw away the work it has already done (A5, PPD-01's habit).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AutomationRun r where r.id = :id")
    Optional<AutomationRun> findByIdForUpdate(@Param("id") Long id);

    /** Runs still finding records, so the sweeper can carry a half-finished fan-out on (A5). */
    @Query("""
            select r.id from AutomationRun r
             where r.status = com.geneinvoice.automation.RunStatus.FANNING
             order by r.id
            """)
    List<Long> findFanning(Pageable page);

    /**
     * Runs whose steps are all planned and are being worked, so the sweeper can notice the last
     * one finishing and CLOSE the run (A5).
     *
     * <p>Nothing else would ever close it. Without this, a rule's first run stays RUNNING for
     * ever, {@link #existsByRuleIdAndStatusIn} answers true at every later slot, and a daily rule
     * records a SKIPPED_OVERRUN every day after its first — which is the overrun check becoming a
     * permanent off switch rather than a safety valve (A5).
     */
    @Query("""
            select r.id from AutomationRun r
             where r.status = com.geneinvoice.automation.RunStatus.RUNNING
             order by r.id
            """)
    List<Long> findRunning(Pageable page);

    /**
     * The overrun check. Racy, and it does not need not to be: {@code uk_run_occasion} is the
     * actual barrier, and this only decides whether the history says SKIPPED_OVERRUN or the
     * insert says it by refusing (A5).
     */
    boolean existsByRuleIdAndStatusIn(Long ruleId, Collection<RunStatus> statuses);

    /**
     * The row a duplicate slot or a double-clicked "run now" bounced off, read back so the second
     * caller is answered with the run the first one opened rather than with an error. It is the
     * SAME key {@code uk_run_occasion} enforces, which is what makes the answer the right one (A5).
     */
    Optional<AutomationRun> findByRuleIdAndOccasion(Long ruleId, String occasion);
}
