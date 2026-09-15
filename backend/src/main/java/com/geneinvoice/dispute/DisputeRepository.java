package com.geneinvoice.dispute;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {
    List<Dispute> findAllByOrderByCreatedAtDesc();
    List<Dispute> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Dispute> findByStatusOrderByCreatedAtDesc(DisputeStatus status);
    List<Dispute> findByTargetTypeAndTargetId(DisputeTargetType targetType, Long targetId);
    boolean existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(Long customerId,
                                                                DisputeTargetType targetType,
                                                                Long targetId,
                                                                DisputeStatus status);
}
