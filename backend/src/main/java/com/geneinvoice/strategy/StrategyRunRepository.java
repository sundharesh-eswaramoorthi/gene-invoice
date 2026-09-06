package com.geneinvoice.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface StrategyRunRepository extends JpaRepository<StrategyRun, Long> {

    boolean existsByStrategyIdAndBusinessDate(Long strategyId, LocalDate businessDate);

    List<StrategyRun> findByStatusAndNextAttemptAtLessThanEqual(RunStatus status, Instant now);

    /** Atomic admission of a due retry: only one caller transitions RETRY_PENDING -> ACTIVE. */
    @Modifying
    @Query("update StrategyRun r set r.status = :newStatus where r.id = :id and r.status = :expected")
    int claimRun(@Param("id") Long id,
                 @Param("expected") RunStatus expected,
                 @Param("newStatus") RunStatus newStatus);
}
