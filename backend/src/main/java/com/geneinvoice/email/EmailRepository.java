package com.geneinvoice.email;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    Optional<Email> findByProviderMessageId(String providerMessageId);

    boolean existsByProviderMessageId(String providerMessageId);

    boolean existsByRfcMessageIdAndDirection(String rfcMessageId, EmailDirection direction);

    List<Email> findByProviderThreadIdOrderByOccurredAtDescIdDesc(String providerThreadId);

    List<Email> findByRfcMessageIdInOrderByOccurredAtDescIdDesc(Collection<String> rfcMessageIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Email e where e.id = :id")
    Optional<Email> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Email e
               set e.status = com.geneinvoice.email.EmailStatus.SENDING,
                   e.attempts = e.attempts + 1,
                   e.updatedAt = :now
             where e.id = :id and e.status = com.geneinvoice.email.EmailStatus.QUEUED
               and e.handedOffAt is null
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :due)
            """)
    int claim(@Param("id") Long id, @Param("due") Instant due, @Param("now") Instant now);

    @Query("""
            select e.id from Email e
             where e.status = com.geneinvoice.email.EmailStatus.QUEUED
               and e.handedOffAt is null
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
               and e.createdAt < :createdBefore
             order by e.id
            """)
    List<Long> findDue(@Param("now") Instant now, @Param("createdBefore") Instant createdBefore, Pageable page);

    @Query("""
            select e.id from Email e
             where e.status = com.geneinvoice.email.EmailStatus.SENDING
               and e.handedOffAt is null
               and e.updatedAt < :touchedBefore
             order by e.id
            """)
    List<Long> findStaleSending(@Param("touchedBefore") Instant touchedBefore, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Email e
               set e.entityDeleted = true
             where e.entityType = :type
               and e.entityId = :entityId
               and (e.entityDeleted is null or e.entityDeleted = false)
            """)
    int markEntityDeleted(@Param("type") EmailEntityType type, @Param("entityId") Long entityId);
}
