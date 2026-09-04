package com.geneinvoice.creditnote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Append-and-read-only store for credit notes. There is deliberately no update or delete
 * path beyond the one-way void handled by the service; every issued row is retained.
 */
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    List<CreditNote> findByInvoiceIdOrderByIssuedAtDesc(Long invoiceId);

    Optional<CreditNote> findByIdAndInvoiceId(Long id, Long invoiceId);

    @Query("select sum(cn.amount) from CreditNote cn where cn.invoice.id = :invoiceId and cn.voided = false")
    BigDecimal sumActiveAmountByInvoiceIdOrNull(Long invoiceId);

    /** Sum of the amounts of all non-voided credit notes of an invoice; zero when there are none. */
    default BigDecimal sumActiveAmountByInvoiceId(Long invoiceId) {
        BigDecimal sum = sumActiveAmountByInvoiceIdOrNull(invoiceId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
