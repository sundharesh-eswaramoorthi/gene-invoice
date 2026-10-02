package com.geneinvoice.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Transactional(readOnly = true)
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);
    @Transactional(readOnly = true)
    List<AuditLog> findByEntityTypeAndEntityIdIn(String entityType, Collection<Long> entityIds);
    @Transactional(readOnly = true)
    List<AuditLog> findByDisputeIdOrderByCreatedAtDesc(Long disputeId);

    /** The region a record's history last named: for a deleted customer, the region it was deleted from. */
    default Optional<Long> findLatestRegionId(String entityType, Long entityId) {
        return regionIdsNewestFirst(entityType, entityId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Who first did this to the record, e.g. who created a customer (the audit row names nobody for a system write). */
    default Optional<Long> findFirstActor(String entityType, Long entityId, String action) {
        return actorsOldestFirst(entityType, entityId, action, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Query("select a.changedByUserId from AuditLog a where a.entityType = :entityType and a.entityId = :entityId"
            + " and a.action = :action and a.changedByUserId is not null order by a.createdAt asc, a.id asc")
    List<Long> actorsOldestFirst(@Param("entityType") String entityType, @Param("entityId") Long entityId,
                                 @Param("action") String action, Pageable page);

    @Query("select a.regionId from AuditLog a where a.entityType = :entityType and a.entityId = :entityId"
            + " and a.regionId is not null order by a.createdAt desc, a.id desc")
    List<Long> regionIdsNewestFirst(@Param("entityType") String entityType, @Param("entityId") Long entityId,
                                    Pageable page);
}
