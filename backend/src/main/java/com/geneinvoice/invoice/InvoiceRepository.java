package com.geneinvoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);
    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);
    /** PESSIMISTIC_WRITE row lock used by the credit-note lifecycle to serialize competing credit actions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findForUpdateById(Long id);
    long countByInvoiceNumberStartingWith(String prefix);
}
