package com.geneinvoice.assignee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RecordAssigneeRepository extends JpaRepository<RecordAssignee, Long> {

    List<RecordAssignee> findByOwnerTypeAndOwnerIdAndRemovedAtIsNullOrderBySortOrderAscIdAsc(String ownerType,
                                                                                         Long ownerId);

    /** The current assignees of a page of owners, in each owner's order. */
    @Query("""
            select a from RecordAssignee a
            where a.ownerType = :ownerType and a.ownerId in :ownerIds and a.removedAt is null
            order by a.ownerId, a.sortOrder, a.id
            """)
    List<RecordAssignee> findActive(@Param("ownerType") String ownerType,
                                    @Param("ownerIds") Collection<Long> ownerIds);

    /** The assignees each owner had at {@code at}: added by then and not yet removed (D8). */
    @Query("""
            select a from RecordAssignee a
            where a.ownerType = :ownerType and a.ownerId in :ownerIds
              and a.addedAt <= :at and (a.removedAt is null or a.removedAt > :at)
            order by a.ownerId, a.sortOrder, a.id
            """)
    List<RecordAssignee> findActiveAt(@Param("ownerType") String ownerType,
                                      @Param("ownerIds") Collection<Long> ownerIds,
                                      @Param("at") Instant at);

    /** How many records name this person now: someone still named is deactivated rather than deleted. */
    long countByKindAndUserIdAndRemovedAtIsNull(String kind, Long userId);
}
