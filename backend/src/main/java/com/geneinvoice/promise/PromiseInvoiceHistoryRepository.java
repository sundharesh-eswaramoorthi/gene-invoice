package com.geneinvoice.promise;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the batched read behind an as-of promise's coverage: which
 * invoices this promise covered ON THE DATE ASKED (B3).
 *
 * <p>A link mirror's {@code id} IS the promise id and the other end is a plain Long beside it,
 * which is why this reads as one flat query with no join — the same shape
 * {@code HistorySchemas.linkPredicate} uses for the {@code invoiceId} filter, so the rows a
 * promise RENDERS and the rows it is FILTERED by come from one table under one rule (B3).
 */
public interface PromiseInvoiceHistoryRepository extends JpaRepository<PromiseInvoiceHistory, Long> {

    @Query("""
            select l from PromiseInvoiceHistory l
             where l.id in :promiseIds
               and l.validFrom <= :at
               and l.validTo > :at
               and l.deleted = false
            """)
    List<PromiseInvoiceHistory> inForce(@Param("promiseIds") Collection<Long> promiseIds,
                                        @Param("at") Instant at);
}
