package com.geneinvoice.automation.run;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AutomationEffectRepository extends JpaRepository<AutomationEffect, Long> {

    /** Which of these keys have acted already. */
    @Query("select e.dedupeKey from AutomationEffect e where e.dedupeKey in :keys")
    List<String> findDoneKeys(@Param("keys") Collection<String> keys);

    /** Whether this run already settled this action on this record (a redelivered event skips it). */
    boolean existsByRunIdAndActionKeyAndTargetTypeAndTargetId(Long runId, String actionKey, String targetType,
                                                              Long targetId);

    boolean existsByDedupeKey(String dedupeKey);

    /** The actions a rule took since {@code since}: what its hourly limit counts (§5.10.6). */
    long countByRuleIdAndStatusAndCreatedAtAfter(Long ruleId, EffectStatus status, Instant since);

    /** The run's effects counted by what came of them: its counters. */
    @Query("select e.status, count(e) from AutomationEffect e where e.runId = :runId group by e.status")
    List<Object[]> countByStatus(@Param("runId") Long runId);

    /** A page of a run's effects, made by the point (as of a date) or ever. */
    @Query("select e from AutomationEffect e where e.runId = :runId and e.createdAt <= :until")
    Page<AutomationEffect> findByRunId(@Param("runId") Long runId, @Param("until") Instant until, Pageable page);

    /** The records a run acted on, and what came of each: the viewer's own counters are counted from these. */
    @Query("select e.targetType, e.targetId, e.status from AutomationEffect e where e.runId = :runId")
    List<Object[]> targetsOf(@Param("runId") Long runId);
}
