package com.geneinvoice.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApprovalThresholdRepository extends JpaRepository<ApprovalThreshold, Long> {

    @Query("select t from ApprovalThreshold t where t.region.id = :regionId and t.operation = :op")
    Optional<ApprovalThreshold> findByRegionIdAndOperation(@Param("regionId") Long regionId,
                                                           @Param("op") ApprovalOperation op);

    @Query("select t from ApprovalThreshold t where t.region.id = :regionId order by t.id")
    List<ApprovalThreshold> findByRegionId(@Param("regionId") Long regionId);

    /** A region's limits, locked: what a change of them is checked against stays put until it is written. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ApprovalThreshold t where t.region.id = :regionId order by t.id")
    List<ApprovalThreshold> findByRegionIdForUpdate(@Param("regionId") Long regionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ApprovalThreshold t where t.region.id = :regionId")
    int deleteByRegionId(@Param("regionId") Long regionId);
}
