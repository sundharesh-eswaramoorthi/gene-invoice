package com.geneinvoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * The mirror's plain repository. Nothing read a mirror through it until B3-SLICE-INVOICE (B3).
 *
 * <p>The sentence above is B3-MIRRORS' and is kept rather than deleted, with the one word that
 * stopped being true corrected: the as-of single-record GET reads an invoice's LINES through
 * {@link #inForce} — the item mirror is the one mirror with no TableSchema of its own, because it
 * is never a list in its own right (B3).
 */
public interface InvoiceItemHistoryRepository extends JpaRepository<InvoiceItemHistory, Long> {

    /**
     * The lines this invoice HAD on the date being answered: one version of each line, the one in
     * force at {@code at}, tombstones dropped (B3).
     *
     * <p>The three clauses are {@code AsOf.at}'s, spelled in JPQL for the same reason
     * {@code PendingChangeRepository.outstandingTargetIdList} spells {@code AsOf.outstandingAt}'s
     * in JPQL: this is a batched child read by foreign key and not a criteria predicate over a
     * query root, so there is no root for a {@code PredicateFactory} to resolve against. Same
     * window, same half-open bounds, same {@code deleted = false} — a second SPELLING of one rule
     * and not a second rule, and {@code AsOfListTest} asserts the two agree.
     *
     * <p>Ordered by the BUSINESS line id, because a mirror's {@code id} is the record and
     * {@code historyId} is the version: ordering by the version would shuffle an invoice's lines
     * every time one of them was edited.
     */
    @Query("""
            select i from InvoiceItemHistory i
             where i.invoiceId = :invoiceId
               and i.validFrom <= :at
               and i.validTo > :at
               and i.deleted = false
             order by i.id asc
            """)
    List<InvoiceItemHistory> inForce(@Param("invoiceId") Long invoiceId, @Param("at") Instant at);
}
