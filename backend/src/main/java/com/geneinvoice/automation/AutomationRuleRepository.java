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
 * COMPLETE ON DAY ONE, so that A-CONSUMER and A-SCHEDULE reopen none of it (A1, A5).
 *
 * <p>Nothing here is a check-then-act. {@link #claimSchedule} returns 1 for the instance that
 * owns this firing and 0 for the one that lost it, which is the cross-instance lock this
 * application otherwise lacks: there is no leader election anywhere in the product and this
 * design needs none.
 */
public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    /**
     * The rules a changed record could possibly arm, read at FAN-OUT time and never at save time.
     *
     * <p>The rules that apply are the rules as they are NOW, not as they were when the event was
     * published: a rule edited between the save and the sweep is applied as edited, and nothing
     * was promised in between. That is also why the user's save costs no read of this table at
     * all (A1).
     */
    @Query("""
            select r from AutomationRule r
             where r.enabled = true and r.deletedAt is null
               and r.subjectType = :subjectType
               and r.triggerKind in :triggerKinds
             order by r.id
            """)
    List<AutomationRule> findArmed(@Param("subjectType") SubjectType subjectType,
                                   @Param("triggerKinds") Collection<TriggerKind> triggerKinds);

    /** Schedules whose hour has come, oldest first; ids only, so the claim is a second statement. */
    @Query("""
            select r.id from AutomationRule r
             where r.enabled = true and r.deletedAt is null
               and r.nextRunAt is not null and r.nextRunAt <= :now
             order by r.id
            """)
    List<Long> findDueSchedules(@Param("now") Instant now, Pageable page);

    /**
     * 1 = this instance owns this firing; 0 = somebody else already advanced it.
     *
     * <p>{@code :next} is computed in Java by {@link TriggerKind#nextAfter}, STRICTLY after now,
     * so a rule missed for three days fires ONCE rather than three times (A1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r
               set r.nextRunAt = :next, r.lastRunAt = :now, r.updatedAt = :now
             where r.id = :id and r.enabled = true and r.deletedAt is null
               and r.nextRunAt is not null and r.nextRunAt <= :now
            """)
    int claimSchedule(@Param("id") Long id, @Param("now") Instant now, @Param("next") Instant next);

    /**
     * PUT THE SLOT BACK when the claim committed and the run did not (A5).
     *
     * <p>{@link #claimSchedule} is the ONLY thing that makes a rule due, and it has already moved
     * {@code next_run_at} on by the time the run is opened in a second transaction. So anything
     * that fails in between — a reset connection, a statement or lock timeout — used to lose that
     * whole slot silently, under a WARN that said the next tick would try again when the very
     * clause it needs had just been made false. This undoes the claim so the sentence is true.
     *
     * <p>Conditional on {@code next_run_at} STILL being the value this instance wrote, so a slot
     * another instance has since claimed and advanced is never dragged backwards. 1 = put back;
     * 0 = somebody else owns the schedule now and there is nothing to undo.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r
               set r.nextRunAt = :due, r.updatedAt = :now
             where r.id = :id and r.nextRunAt = :claimed
            """)
    int releaseSchedule(@Param("id") Long id, @Param("claimed") Instant claimed,
                        @Param("due") Instant due, @Param("now") Instant now);
}
