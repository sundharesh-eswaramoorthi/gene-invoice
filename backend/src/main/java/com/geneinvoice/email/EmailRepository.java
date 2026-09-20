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

    /**
     * The email, locked until the transaction ends, so the dispatcher and the mail service's reports
     * never roll its copies up over each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Email e where e.id = :id")
    Optional<Email> findByIdForUpdate(@Param("id") Long id);

    /**
     * Takes a queued email for its hand-off to the mail service. Only one caller can win it, so the
     * sweeper and a request dispatching the same email never both hand it over, and nobody takes a
     * retry before its wait is over as of {@code due}. Once handed off, the email is the service's.
     * Clears the persistence context, which would otherwise keep serving the pre-update status.
     */
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

    /**
     * Queued emails not yet handed off whose retry is due, old enough that the request that saved
     * them is not still handing them over.
     */
    @Query("""
            select e.id from Email e
             where e.status = com.geneinvoice.email.EmailStatus.QUEUED
               and e.handedOffAt is null
               and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
               and e.createdAt < :createdBefore
             order by e.id
            """)
    List<Long> findDue(@Param("now") Instant now, @Param("createdBefore") Instant createdBefore, Pageable page);

    /**
     * Hand-offs nobody has touched since {@code touchedBefore}, which died half way. The dispatcher
     * settles each with the email locked (it fails their queued copies and rolls the email up), so
     * a hand-off that finished in the meantime is left alone.
     */
    @Query("""
            select e.id from Email e
             where e.status = com.geneinvoice.email.EmailStatus.SENDING
               and e.handedOffAt is null
               and e.updatedAt < :touchedBefore
             order by e.id
            """)
    List<Long> findStaleSending(@Param("touchedBefore") Instant touchedBefore, Pageable page);

    /**
     * Marks every email recorded against a record that has just been deleted, so its views stop
     * offering a link to something that is no longer there (CP-13). Clears the persistence
     * context, which would otherwise keep serving the emails as they were.
     */
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
