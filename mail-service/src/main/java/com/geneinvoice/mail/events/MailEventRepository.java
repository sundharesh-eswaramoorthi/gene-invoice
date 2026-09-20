package com.geneinvoice.mail.events;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MailEventRepository extends JpaRepository<MailEvent, Long> {

    /** The oldest events the backend has not taken yet, in the order they happened. */
    @Query("select e from MailEvent e where e.deliveredAt is null order by e.id")
    List<MailEvent> findUndelivered(Pageable page);

    List<MailEvent> findByTypeOrderByIdAsc(String type);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailEvent e set e.deliveredAt = :at where e.id in :ids")
    int markDelivered(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MailEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id in :ids")
    int markFailed(@Param("ids") Collection<Long> ids, @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MailEvent e where e.deliveredAt is not null and e.deliveredAt < :before")
    int deleteDeliveredBefore(@Param("before") Instant before);
}
