package com.geneinvoice.invoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);
    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);
    long countByInvoiceNumberStartingWith(String prefix);

    /**
     * Locked reads for financial writers: a transaction that will read an
     * invoice's total, paidAmount, credit contribution or settlement status in
     * order to change it must load the invoice through one of these so the
     * row is write-locked until commit. Multi-invoice variants return rows in
     * the canonical invoiceDate-then-id order so every bulk writer acquires
     * its locks in the same order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.customer.id = :customerId order by i.invoiceDate, i.id")
    List<Invoice> findByCustomerIdForUpdate(@Param("customerId") Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id in :ids order by i.invoiceDate, i.id")
    List<Invoice> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}
