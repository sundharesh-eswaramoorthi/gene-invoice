package com.geneinvoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);
    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);
    long countByInvoiceNumberStartingWith(String prefix);
}
