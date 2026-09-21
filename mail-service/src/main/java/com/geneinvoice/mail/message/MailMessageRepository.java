package com.geneinvoice.mail.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {

    Optional<MailMessage> findByExternalId(String externalId);

    List<MailMessage> findByExternalIdIn(Collection<String> externalIds);

    boolean existsByProviderMessageId(String providerMessageId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MailMessage m
               set m.status = com.geneinvoice.mail.message.MessageStatus.SENDING,
                   m.attempts = m.attempts + 1,
                   m.seq = m.seq + 1,
                   m.updatedAt = :now
             where m.id = :id and m.status = com.geneinvoice.mail.message.MessageStatus.QUEUED
               and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailMessage m set m.enqueuedAt = :at where m.id in :ids")
    int markEnqueued(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    @Query("""
            select m.id from MailMessage m
             where m.status = com.geneinvoice.mail.message.MessageStatus.SENDING and m.updatedAt < :touchedBefore
             order by m.id
            """)
    List<Long> findStaleSending(@Param("touchedBefore") Instant touchedBefore, Pageable page);

    @Query("""
            select m.id from MailMessage m
             where m.status = com.geneinvoice.mail.message.MessageStatus.QUEUED
               and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
               and (m.enqueuedAt is null or m.enqueuedAt < :enqueuedBefore)
             order by m.id
            """)
    List<Long> findUnpublished(@Param("now") Instant now, @Param("enqueuedBefore") Instant enqueuedBefore,
                               Pageable page);

    @Query("""
            select m from MailMessage m
             where m.status in (com.geneinvoice.mail.message.MessageStatus.SENT,
                                com.geneinvoice.mail.message.MessageStatus.DELIVERED)
               and m.recipientMessageId is null
               and m.sentAt >= :sentSince
               and exists (select c.id from MailConnection c
                            where c.gmailAddress = m.toAddressKey
                              and c.status = com.geneinvoice.mail.connection.ConnectionStatus.CONNECTED)
             order by m.sentAt, m.id
            """)
    List<MailMessage> findToConfirm(@Param("sentSince") Instant sentSince, Pageable page);

    @Query("""
            select m.id from MailMessage m
             where m.status = com.geneinvoice.mail.message.MessageStatus.SENT and m.sentAt < :sentBefore
             order by m.sentAt, m.id
            """)
    List<Long> findToEstimate(@Param("sentBefore") Instant sentBefore, Pageable page);

    /** Which of these threads hold a copy sent from the connection: the only mail a sync looks at (M10). */
    @Query("""
            select distinct m.providerThreadId from MailMessage m
             where m.connectionId = :connectionId and m.providerThreadId in :threadIds
            """)
    List<String> findOwnThreads(@Param("connectionId") Long connectionId,
                                @Param("threadIds") Collection<String> threadIds);

    @Query("""
            select m from MailMessage m
             where m.connectionId = :connectionId and m.providerThreadId = :threadId
             order by m.sentAt desc nulls last, m.id desc
            """)
    List<MailMessage> findInThread(@Param("connectionId") Long connectionId, @Param("threadId") String threadId);

    Optional<MailMessage> findFirstByConnectionIdAndRfcMessageIdOrderByIdDesc(Long connectionId, String rfcMessageId);

    @Query("""
            select m.recipientMessageId from MailMessage m
             where m.recipientConnectionId = :connectionId and m.recipientMessageId is not null
               and m.status in (com.geneinvoice.mail.message.MessageStatus.SENT,
                                com.geneinvoice.mail.message.MessageStatus.DELIVERED)
               and m.sentAt >= :sentSince
             order by m.sentAt, m.id
            """)
    List<String> findUnreadInRecipientMailbox(@Param("connectionId") Long connectionId,
                                              @Param("sentSince") Instant sentSince);

    @Query("""
            select m.id from MailMessage m
             where m.recipientConnectionId = :connectionId and m.recipientMessageId = :messageId
            """)
    List<Long> findIdsInRecipientMailbox(@Param("connectionId") Long connectionId, @Param("messageId") String messageId);
}
