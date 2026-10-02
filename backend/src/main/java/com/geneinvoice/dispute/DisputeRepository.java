package com.geneinvoice.dispute;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id")
    Optional<Dispute> findByIdForUpdate(@Param("id") Long id);

    /** The customer alone, read without a lock: what a decision locks first, before the dispute (§5.11.5). */
    @Query("select d.customerId from Dispute d where d.id = :id")
    Optional<Long> customerIdOf(@Param("id") Long id);

    /** Who opened the dispute: for an internal one, the owner of the rule that raised it (check 5a, §5.11.6). */
    @Query("select d.openedByUserId from Dispute d where d.id = :id")
    Optional<Long> openerOf(@Param("id") Long id);

    /** The internal disputes among these: what a customer login's trail leaves out (SC-1). */
    @Query("select d.id from Dispute d where d.internal = true and d.id in :ids")
    List<Long> findInternalAmong(@Param("ids") Collection<Long> ids);

    @Transactional(readOnly = true)
    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    @Transactional(readOnly = true)
    List<Dispute> findByStatusOrderByCreatedAtDesc(DisputeStatus status);
    @Transactional(readOnly = true)
    List<Dispute> findByTargetTypeAndTargetId(DisputeTargetType targetType, Long targetId);

    /** A customer's own open dispute on the record; a pending internal one neither counts nor shows (SC-1). */
    boolean existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalFalse(Long customerId,
                                                                                DisputeTargetType targetType,
                                                                                Long targetId,
                                                                                DisputeStatus status);

    /** An internal dispute still pending on the record: automation raises one at a time (§5.10.11). */
    boolean existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalTrue(Long customerId,
                                                                               DisputeTargetType targetType,
                                                                               Long targetId,
                                                                               DisputeStatus status);
}
