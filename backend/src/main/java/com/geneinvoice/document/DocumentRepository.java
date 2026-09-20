package com.geneinvoice.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * The document, locked until the transaction ends. A delete reads the row through this, so two
     * deletes of one document line up: the second waits, then reads the first's soft delete and
     * answers 404, rather than both reading a live row and each writing its own deletion — and its
     * own audit entry — over the other's (DOC-3, AC-C3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    long countByEntityTypeAndEntityIdAndDeletedFalse(DocumentEntityType entityType, Long entityId);

    /** What a customer login counts on one of its records: the shared ones only (D7). */
    long countByEntityTypeAndEntityIdAndDeletedFalseAndVisibility(
            DocumentEntityType entityType, Long entityId, DocumentVisibility visibility);

    List<Document> findByCustomerIdAndDeletedFalseOrderByIdAsc(Long customerId);

    /**
     * Soft-deletes every live document of one customer — its own, and those on its invoices and
     * payments — in one statement, as the customer itself is deleted (AC-C5). Clears the
     * persistence context, which would otherwise keep serving the pre-update rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.deleted = true,
                   d.deletedByUserId = :userId,
                   d.deletedAt = :now,
                   d.updatedAt = :now
             where d.customerId = :customerId and d.deleted = false
            """)
    int softDeleteForCustomer(@Param("customerId") Long customerId,
                              @Param("userId") Long userId,
                              @Param("now") Instant now);
}
