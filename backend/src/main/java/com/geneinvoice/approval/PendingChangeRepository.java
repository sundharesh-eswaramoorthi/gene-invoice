package com.geneinvoice.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PendingChangeRepository extends JpaRepository<PendingChange, Long> {

    @Transactional(readOnly = true)
    Optional<PendingChange> findByOutstandingKey(String outstandingKey);

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PendingChange p where p.id = :id")
    Optional<PendingChange> findByIdForUpdate(@Param("id") Long id);

    /** Changes in this status on a record, as the record itself or as the one whose money they move; locked. */
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from PendingChange p
            where p.status = :status
              and ((p.entityType = :type and p.entityId = :id) or (p.affectedType = :type and p.affectedId = :id))
            order by p.id
            """)
    List<PendingChange> findOnRecordForUpdate(@Param("type") String type, @Param("id") Long id,
                                              @Param("status") PendingStatus status);

    /** Every change in this status for a customer, its creates included; locked. */
    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PendingChange p where p.status = :status and p.customerId = :customerId order by p.id")
    List<PendingChange> findForCustomerForUpdate(@Param("customerId") Long customerId,
                                                 @Param("status") PendingStatus status);

    /** The maker alone, read without a lock: an approval binds its provenance before its transaction starts. */
    @Query("select p.makerUserId from PendingChange p where p.id = :id")
    Optional<Long> findMakerId(@Param("id") Long id);

    /** The change this one replaced, if the same maker asked again for the same record. */
    @Transactional(readOnly = true)
    Optional<PendingChange> findFirstBySupersededById(Long supersededById);

    /**
     * What one maker still has waiting of one kind for one customer in a region (the cumulative window, SC-9),
     * leaving out the change under {@code exceptKey}, which the change being measured would replace.
     */
    @Query("""
            select coalesce(sum(p.amount), 0) from PendingChange p
            where p.status = :status and p.makerUserId = :maker and p.region.id = :region and p.operation = :op
              and p.customerId = :customerId and coalesce(p.outstandingKey, '') <> :exceptKey
            """)
    BigDecimal sumPendingBy(@Param("maker") Long makerUserId, @Param("region") Long regionId,
                                      @Param("op") ApprovalOperation op, @Param("customerId") Long customerId,
                                      @Param("exceptKey") String exceptKey, @Param("status") PendingStatus status);

    /** A user named on a change as its maker or decider is deactivated, not deleted. */
    long countByMakerUserIdOrDecidedByUserId(Long makerUserId, Long decidedByUserId);

    /** Whether a change of this kind in a region is in this status. */
    @Query("""
            select count(p) > 0 from PendingChange p
            where p.region.id = :region and p.operation = :op and p.status = :status
            """)
    boolean existsInRegion(@Param("region") Long regionId, @Param("op") ApprovalOperation op,
                           @Param("status") PendingStatus status);

    /** Whether any change other than of this kind was ever asked for in a region. */
    @Query("select count(p) > 0 from PendingChange p where p.region.id = :region and p.operation <> :op")
    boolean existsInRegionOtherThan(@Param("region") Long regionId, @Param("op") ApprovalOperation op);

    @Modifying(flushAutomatically = true)
    @Query("delete from PendingChange p where p.region.id = :region and p.operation = :op")
    int deleteInRegion(@Param("region") Long regionId, @Param("op") ApprovalOperation op);
}
