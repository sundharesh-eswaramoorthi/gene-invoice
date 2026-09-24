package com.geneinvoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the three batched as-of reads that are NOT list queries and
 * therefore have no query root for a {@code PredicateFactory} to resolve against (B3).
 *
 * <p>The three interval clauses below are {@link com.geneinvoice.common.asof.AsOf#at}'s, spelled
 * in JPQL for exactly the reason {@code InvoiceItemHistoryRepository.inForce} spells them: these
 * are batched child reads keyed by a foreign key, run to fill a DTO the executor has already
 * chosen the rows for. Same window, same half-open bounds, same {@code deleted = false} — a
 * second SPELLING of one rule and not a second rule.
 */
public interface InvoiceHistoryRepository extends JpaRepository<InvoiceHistory, Long> {

    /**
     * The versions of these invoices in force at {@code at}, one row per invoice. Used wherever an
     * as-of read has to render an invoice it did not root on — a payment's allocations, a promise's
     * coverage, a dispute's target (B3).
     */
    @Query("""
            select i from InvoiceHistory i
             where i.id in :ids
               and i.validFrom <= :at
               and i.validTo > :at
               and i.deleted = false
            """)
    List<InvoiceHistory> inForce(@Param("ids") Collection<Long> ids, @Param("at") Instant at);

    /**
     * What these accounts owed on the date asked about — the batched twin of the
     * {@code outstanding} column HistorySchemas.CUSTOMERS sorts and filters by, so the number a
     * customer row RENDERS and the number it is ORDERED by are the same number (B3).
     *
     * <p>Byte-for-byte the live {@code InvoiceRepository.sumOutstandingByCustomer} with the flat
     * {@code customerId} in place of the association walk and the interval clause added.
     */
    @Query("""
            select i.customerId, coalesce(sum(i.total - i.paidAmount), 0)
              from InvoiceHistory i
             where i.customerId in :ids
               and i.status <> com.geneinvoice.invoice.InvoiceStatus.CANCELLED
               and i.validFrom <= :at
               and i.validTo > :at
               and i.deleted = false
             group by i.customerId
            """)
    List<Object[]> sumOutstandingByCustomerAsOf(@Param("ids") Collection<Long> ids,
                                                @Param("at") Instant at);

    /**
     * What was overdue on the date asked about, aged against THAT date: {@code today} is
     * {@code InvoiceDates.today()}, which under an open context IS the as-of date (B3).
     */
    @Query("""
            select i.customerId, coalesce(sum(i.total - i.paidAmount), 0)
              from InvoiceHistory i
             where i.customerId in :ids
               and i.status <> com.geneinvoice.invoice.InvoiceStatus.CANCELLED
               and i.dueDate < :today
               and i.total - i.paidAmount > 0
               and i.validFrom <= :at
               and i.validTo > :at
               and i.deleted = false
             group by i.customerId
            """)
    List<Object[]> sumOverdueByCustomerAsOf(@Param("ids") Collection<Long> ids,
                                            @Param("today") LocalDate today,
                                            @Param("at") Instant at);
}
