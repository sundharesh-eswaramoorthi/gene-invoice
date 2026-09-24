package com.geneinvoice.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the one batched as-of read the payments list needs: a
 * payment row carries the account's CREDIT BALANCE, and on a past page that has to be the balance
 * the account held then rather than the one it holds today (B3).
 */
public interface CustomerHistoryRepository extends JpaRepository<CustomerHistory, Long> {

    /**
     * The versions of these accounts in force at {@code at}, one row per account. The interval
     * clauses are {@link com.geneinvoice.common.asof.AsOf#at}'s spelled in JPQL, because this is a
     * batched child read by id and not a predicate over a query root (B3).
     */
    @Query("""
            select c from CustomerHistory c
             where c.id in :ids
               and c.validFrom <= :at
               and c.validTo > :at
               and c.deleted = false
            """)
    List<CustomerHistory> inForce(@Param("ids") Collection<Long> ids, @Param("at") Instant at);
}
