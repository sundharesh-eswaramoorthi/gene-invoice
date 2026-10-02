package com.geneinvoice.automation.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, Long> {

    /** Runs of these rules that failed, finished within {@code (from, to]} and created by {@code to}. */
    @Query("""
            select count(r) from AutomationRun r
             where r.ruleId in :ruleIds and r.status = com.geneinvoice.automation.run.RunStatus.FAILED
               and r.finishedAt > :from and r.finishedAt <= :to and r.createdAt <= :to
            """)
    long countFailed(@Param("ruleIds") Collection<Long> ruleIds, @Param("from") Instant from, @Param("to") Instant to);

    /** The one run of a rule for an event (uk_automation_run). */
    Optional<AutomationRun> findByRuleIdAndEventId(Long ruleId, Long eventId);

    List<AutomationRun> findByEventIdOrderByIdAsc(Long eventId);

    /** The runs of an event that have not finished: what giving up on the event fails. */
    @Query("""
            select r from AutomationRun r where r.eventId = :eventId
               and r.status in (com.geneinvoice.automation.run.RunStatus.QUEUED,
                                com.geneinvoice.automation.run.RunStatus.RUNNING)
             order by r.id
            """)
    List<AutomationRun> findUnfinishedByEventId(@Param("eventId") Long eventId);

    /** Counters written as the run goes, without touching the rest of the row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AutomationRun r set r.matched = :matched, r.truncated = :truncated where r.id = :id")
    int setProgress(@Param("id") Long id, @Param("matched") int matched, @Param("truncated") boolean truncated);
}
