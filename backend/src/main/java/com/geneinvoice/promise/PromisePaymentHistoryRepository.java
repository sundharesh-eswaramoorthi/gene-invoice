package com.geneinvoice.promise;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the batched read behind an as-of promise's payments: which
 * payments were credited against this promise ON THE DATE ASKED (B3).
 *
 * <p>A link mirror's {@code id} IS the promise id and the other end is a plain Long beside it, so
 * this is one flat query with no join — the same shape the {@code paymentId} filter uses (B3).
 */
public interface PromisePaymentHistoryRepository extends JpaRepository<PromisePaymentHistory, Long> {

    @Query("""
            select l from PromisePaymentHistory l
             where l.id in :promiseIds
               and l.validFrom <= :at
               and l.validTo > :at
               and l.deleted = false
            """)
    List<PromisePaymentHistory> inForce(@Param("promiseIds") Collection<Long> promiseIds,
                                        @Param("at") Instant at);
}
