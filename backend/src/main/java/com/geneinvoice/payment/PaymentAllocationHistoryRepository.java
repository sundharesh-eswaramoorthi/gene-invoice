package com.geneinvoice.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the batched read behind every as-of payment's allocations:
 * which invoices this payment was applied to ON THE DATE ASKED, and for how much (B3).
 */
public interface PaymentAllocationHistoryRepository extends JpaRepository<PaymentAllocationHistory, Long> {

    /**
     * The allocations of these payments in force at {@code at}. Ordered by the BUSINESS id, because
     * a mirror's {@code id} is the record and {@code historyId} is the version: ordering by the
     * version would reshuffle a payment's invoice list every time one of them was edited (B3).
     */
    @Query("""
            select a from PaymentAllocationHistory a
             where a.paymentId in :paymentIds
               and a.validFrom <= :at
               and a.validTo > :at
               and a.deleted = false
             order by a.id asc
            """)
    List<PaymentAllocationHistory> inForce(@Param("paymentIds") Collection<Long> paymentIds,
                                           @Param("at") Instant at);
}
