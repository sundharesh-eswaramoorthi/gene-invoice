package com.geneinvoice.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** The mirror's plain repository, plus the one batched as-of read a promise's payments need (B3). */
public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    /**
     * The versions of these payments in force at {@code at}, one row per payment. The interval
     * clauses are {@link com.geneinvoice.common.asof.AsOf#at}'s spelled in JPQL, because this is a
     * batched child read by id and not a predicate over a query root (B3).
     */
    @Query("""
            select p from PaymentHistory p
             where p.id in :ids
               and p.validFrom <= :at
               and p.validTo > :at
               and p.deleted = false
            """)
    List<PaymentHistory> inForce(@Param("ids") Collection<Long> ids, @Param("at") Instant at);
}
