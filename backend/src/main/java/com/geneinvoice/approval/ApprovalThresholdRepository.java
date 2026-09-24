package com.geneinvoice.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApprovalThresholdRepository extends JpaRepository<ApprovalThreshold, Long> {

    Optional<ApprovalThreshold> findByRegionId(Long regionId);

    // Two appliers landing two threshold changes for one region serialise on the row rather than
    // both reading the old amount and one of them overwriting the other (B2).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ApprovalThreshold t where t.regionId = :regionId")
    Optional<ApprovalThreshold> findByRegionIdForUpdate(@Param("regionId") Long regionId);
}
