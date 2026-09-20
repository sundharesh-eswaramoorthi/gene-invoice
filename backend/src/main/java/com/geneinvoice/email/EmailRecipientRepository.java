package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface EmailRecipientRepository extends JpaRepository<EmailRecipient, Long> {

    List<EmailRecipient> findByEmailIdOrderByIdAsc(Long emailId);

    List<EmailRecipient> findByEmailIdInOrderByIdAsc(Collection<Long> emailIds);

    long countByUserIdAndFieldAndReadFalse(Long userId, RecipientField field);

    @Modifying
    @Query("""
            update EmailRecipient r set r.read = true, r.readAt = :now
             where r.userId = :userId
               and r.field = com.geneinvoice.email.RecipientField.TO
               and r.read = false
            """)
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * One of the user's own Inbox rows read, keeping when it was first read. Only these two columns
     * are written: the row's copy may be changing under a report from the mail service meanwhile.
     * Returns 0 when it is not the user's To row or was read already.
     */
    @Modifying
    @Query("""
            update EmailRecipient r set r.read = true, r.readAt = :now
             where r.id = :id and r.userId = :userId
               and r.field = com.geneinvoice.email.RecipientField.TO
               and r.read = false
            """)
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    /** Unread again, forgetting when it was read; 0 when it is not the user's To row or was unread already. */
    @Modifying
    @Query("""
            update EmailRecipient r set r.read = false, r.readAt = null
             where r.id = :id and r.userId = :userId
               and r.field = com.geneinvoice.email.RecipientField.TO
               and r.read = true
            """)
    int markUnread(@Param("id") Long id, @Param("userId") Long userId);

    // ---- copies, for received mail to find the email it answers --------------------------

    /** Copies sent in a Gmail thread, with their emails. */
    @Query("select r from EmailRecipient r join fetch r.email where r.providerThreadId = :threadId")
    List<EmailRecipient> findCopiesInThread(@Param("threadId") String threadId);

    /** Copies that went out with one of these Message-IDs, with their emails. */
    @Query("select r from EmailRecipient r join fetch r.email where r.rfcMessageId in :rfcMessageIds")
    List<EmailRecipient> findCopiesByRfcMessageIdIn(@Param("rfcMessageIds") Collection<String> rfcMessageIds);

    boolean existsByProviderMessageId(String providerMessageId);

    boolean existsByRfcMessageId(String rfcMessageId);
}
