package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailRecipientReadRepository extends JpaRepository<EmailRecipientRead, Long> {

    Optional<EmailRecipientRead> findByEmail_IdAndUserId(Long emailId, Long userId);

    /** One owner-scoped update across every unread row, including rows off the visible page (EDGE3). */
    @Modifying
    @Query("update EmailRecipientRead r set r.read = true where r.userId = :userId and r.read = false")
    int markAllRead(@Param("userId") Long userId);
}
