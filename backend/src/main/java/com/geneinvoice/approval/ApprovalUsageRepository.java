package com.geneinvoice.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

public interface ApprovalUsageRepository extends JpaRepository<ApprovalUsage, Long> {

    @Query("""
            select coalesce(sum(u.amount), 0) from ApprovalUsage u
            where u.makerUserId = :maker and u.regionId = :region and u.operation = :op
              and u.customerId = :customerId and u.usedAt > :since
            """)
    BigDecimal sumSince(@Param("maker") Long makerUserId, @Param("region") Long regionId,
                        @Param("op") ApprovalOperation op, @Param("customerId") Long customerId,
                        @Param("since") Instant since);

    @Modifying
    @Transactional
    @Query("delete from ApprovalUsage u where u.usedAt < :before")
    int deleteUsedBefore(@Param("before") Instant before);
}
