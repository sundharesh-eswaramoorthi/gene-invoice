package com.geneinvoice.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    /** Fetch-joins structured invoice items so the DTO mapping can read them lazily-free. */
    @Query("select distinct n from Notification n left join fetch n.invoiceItems " +
            "where n.userId = :userId order by n.createdAt desc")
    List<Notification> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
    long countByUserIdAndReadFalse(Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId);
}
