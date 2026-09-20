package com.geneinvoice.mail.connection;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MailConnectionRepository extends JpaRepository<MailConnection, Long> {

    Optional<MailConnection> findByOwnerRef(String ownerRef);

    List<MailConnection> findAllByOrderByOwnerNameAscIdAsc();

    @Query("select c.id from MailConnection c where c.status = :status order by c.id")
    List<Long> findIdsByStatus(@Param("status") ConnectionStatus status);

    /** A mailbox the app can read, by its Gmail address (lower case); the oldest when two owners share one. */
    Optional<MailConnection> findFirstByGmailAddressAndStatusOrderByIdAsc(String gmailAddress, ConnectionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MailConnection c where c.id = :id")
    Optional<MailConnection> findByIdForUpdate(@Param("id") Long id);

    /**
     * A sync that finished. An update query leaves {@code version} alone, so the cached access token
     * stays valid; it writes nothing when the connection changed (reconnected, disconnected) meanwhile.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MailConnection c
               set c.historyId = :historyId, c.lastSyncedAt = :at, c.lastSyncError = null
             where c.id = :id and c.version = :version
            """)
    int recordSync(@Param("id") Long id, @Param("version") Long version, @Param("historyId") String historyId,
                   @Param("at") Instant at);

    /** A sync that stopped: its place is kept, and why it stopped is shown. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailConnection c set c.lastSyncError = :error where c.id = :id and c.version = :version")
    int recordSyncError(@Param("id") Long id, @Param("version") Long version, @Param("error") String error);
}
