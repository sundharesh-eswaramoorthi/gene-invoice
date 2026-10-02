package com.geneinvoice.region;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The move log, and the statements that move a customer's rows. {@code region_id} is never written by an ORM
 * update (F3), so the move is plain SQL; the service names every row it moved to {@code HistoryTracker.touch} in
 * the same transaction, which is what allows SQL here (§5.7.6). Invoices and the customer carry a row version,
 * which the move bumps, so an edit composed before the move is refused (409) rather than applied over it.
 */
public interface CustomerRegionMoveRepository extends JpaRepository<CustomerRegionMove, Long> {

    List<CustomerRegionMove> findByCustomerIdOrderByMovedAtAscIdAsc(Long customerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update invoices set region_id = :to, version = version + 1 where customer_id = :cid",
            nativeQuery = true)
    int moveInvoices(@Param("cid") Long customerId, @Param("to") Long toRegionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update payments set region_id = :to where customer_id = :cid", nativeQuery = true)
    int movePayments(@Param("cid") Long customerId, @Param("to") Long toRegionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update payment_promises set region_id = :to where customer_id = :cid", nativeQuery = true)
    int movePromises(@Param("cid") Long customerId, @Param("to") Long toRegionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update disputes set region_id = :to where customer_id = :cid", nativeQuery = true)
    int moveDisputes(@Param("cid") Long customerId, @Param("to") Long toRegionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update customers set region_id = :to, version = version + 1 where id = :cid",
            nativeQuery = true)
    int moveCustomer(@Param("cid") Long customerId, @Param("to") Long toRegionId);
}
