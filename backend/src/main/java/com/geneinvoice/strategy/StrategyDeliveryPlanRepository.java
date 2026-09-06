package com.geneinvoice.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StrategyDeliveryPlanRepository extends JpaRepository<StrategyDeliveryPlan, Long> {

    List<StrategyDeliveryPlan> findByStateAndNextAttemptAtLessThanEqual(PlanState state, Instant now);

    /** Atomic claim of a due plan: only one worker transitions PENDING -> IN_PROGRESS. */
    @Modifying
    @Query("update StrategyDeliveryPlan p set p.state = :newState where p.id = :id and p.state = :expected")
    int claimPlan(@Param("id") Long id,
                  @Param("expected") PlanState expected,
                  @Param("newState") PlanState newState);
}
