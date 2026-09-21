package com.geneinvoice.assignee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AssigneeRepository extends JpaRepository<Assignee, Long> {

    List<Assignee> findByOwnerTypeAndOwnerIdOrderByIdAsc(AssigneeOwnerType ownerType, Long ownerId);

    /** Every owner's rows in one read, for a list page that would otherwise ask once per row. */
    List<Assignee> findByOwnerTypeAndOwnerIdInOrderByIdAsc(AssigneeOwnerType ownerType, Collection<Long> ownerIds);

    /**
     * Replaces one record's assignees: the old rows go, the new ones are written by the caller.
     * Clears the persistence context, which would otherwise keep serving the rows just deleted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Assignee a where a.ownerType = :ownerType and a.ownerId = :ownerId")
    int deleteForOwner(@Param("ownerType") AssigneeOwnerType ownerType, @Param("ownerId") Long ownerId);

    /**
     * Drops every assignee of a customer's records as the customer itself is deleted, so a task or
     * promise row never outlives the customer it pointed at and blocks the delete (AC-C5).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Assignee a where a.customerId = :customerId")
    int deleteForCustomer(@Param("customerId") Long customerId);
}
