package com.geneinvoice.email.connection;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GmailConnection g where g.userId = :userId")
    Optional<GmailConnection> findByIdForUpdate(@Param("userId") Long userId);

    @Modifying
    @Query(value = "insert into gmail_connections (user_id, status, updated_at) values (:userId, 'DISCONNECTED', :now)",
            nativeQuery = true)
    int insertBlank(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("""
            select g.userId from GmailConnection g
             where g.disconnectRequestedAt is not null
             order by g.disconnectRequestedAt, g.userId
            """)
    List<Long> findDisconnectsDue(Pageable page);
}
