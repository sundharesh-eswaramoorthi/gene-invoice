package com.geneinvoice.creditnote;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Invoice-scoped persistence seam for credit notes. Rows are permanent: no delete
 * operation is declared or used anywhere; a note that reverses is VOIDED, never
 * removed. The history finder returns ACTIVE and VOIDED rows together in one
 * deterministic order; the aggregate counts ACTIVE rows only and defaults to zero;
 * the single-note lookup holds a pessimistic write lock for the void transition.
 */
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    /**
     * Complete history of one invoice: ACTIVE and VOIDED notes together, ordered
     * deterministically by issue time and then id (id breaks ties between notes
     * issued within the same instant).
     */
    List<CreditNote> findByInvoiceIdOrderByIssuedAtAscIdAsc(Long invoiceId);

    /**
     * Raw sum of the amounts of one invoice's ACTIVE notes; null when the invoice
     * has no active notes. Use {@link #sumActiveAmountForInvoice(Long)}, which
     * converts the empty case to zero.
     */
    @Query("select sum(cn.amount) from CreditNote cn "
            + "where cn.invoice.id = :invoiceId "
            + "and cn.status = com.geneinvoice.creditnote.CreditNoteStatus.ACTIVE")
    BigDecimal rawActiveAmountSumForInvoice(Long invoiceId);

    /**
     * Amount currently credited against an invoice: the sum of its ACTIVE notes'
     * amounts, or zero when none exist. A voided note stops contributing exactly
     * its amount with no counter stored anywhere. The zero default is applied in
     * Java rather than JPQL so it never depends on the persistence provider's
     * numeric-literal promotion of a coalesce fallback.
     */
    default BigDecimal sumActiveAmountForInvoice(Long invoiceId) {
        BigDecimal sum = rawActiveAmountSumForInvoice(invoiceId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * Load one note while holding its row's pessimistic write lock for the calling
     * transaction. The void transition must acquire the note through this finder
     * so a concurrent void of the same row serializes instead of double-restoring
     * its amount.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cn from CreditNote cn where cn.id = :id")
    Optional<CreditNote> findByIdForUpdate(Long id);
}
