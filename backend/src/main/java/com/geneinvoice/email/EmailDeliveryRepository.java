package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {

    Optional<EmailDelivery> findByEmailIdAndUserId(Long emailId, Long userId);

    List<EmailDelivery> findByEmailId(Long emailId);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** Every unread email in this user's Inbox, on every page; nobody else's copies. */
    @Modifying
    @Query("update EmailDelivery d set d.readAt = :now where d.userId = :userId and d.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from EmailDelivery d where d.email.id in (select e.id from Email e where e.customer.id = :customerId)")
    int deleteForCustomer(@Param("customerId") Long customerId);
}
