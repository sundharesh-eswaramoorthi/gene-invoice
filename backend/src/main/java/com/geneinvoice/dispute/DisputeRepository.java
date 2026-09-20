package com.geneinvoice.dispute;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /**
     * The dispute, locked until the transaction ends, for the resolve path: two approvals of one
     * dispute settle in turn, so the second reads the status the first wrote rather than both
     * passing the same pending check and racing on the money (PPD-03).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id")
    Optional<Dispute> findByIdForUpdate(@Param("id") Long id);

    List<Dispute> findAllByOrderByCreatedAtDesc();
    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Dispute> findByStatusOrderByCreatedAtDesc(DisputeStatus status);
    List<Dispute> findByTargetTypeAndTargetId(DisputeTargetType targetType, Long targetId);
    boolean existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(Long customerId,
                                                                DisputeTargetType targetType,
                                                                Long targetId,
                                                                DisputeStatus status);
}
