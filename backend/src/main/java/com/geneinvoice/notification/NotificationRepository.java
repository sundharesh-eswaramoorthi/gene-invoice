package com.geneinvoice.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndReadFalse(Long userId);
    long countByUserIdAndReadFalseAndRegionIdIsNull(Long userId);

    @Query("select count(n) from Notification n where n.userId = :userId and n.read = false"
            + " and (n.regionId is null or n.regionId in :regionIds)")
    long countUnreadInRegions(@Param("userId") Long userId, @Param("regionIds") Collection<Long> regionIds);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false"
            + " and n.regionId is null")
    int markAllReadAboutNoRegion(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :userId and n.read = false"
            + " and (n.regionId is null or n.regionId in :regionIds)")
    int markAllReadInRegions(@Param("userId") Long userId, @Param("regionIds") Collection<Long> regionIds);
}
