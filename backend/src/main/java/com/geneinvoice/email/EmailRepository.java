package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmailRepository extends JpaRepository<Email, Long> {

    List<Email> findByCustomer_IdOrderBySentAtDescIdDesc(Long customerId);

    List<Email> findByInvoice_IdOrderBySentAtDescIdDesc(Long invoiceId);

    /** Emails linked to any of the customer's invoices, with the invoice fetched for its number. */
    @Query("""
            select e from Email e join fetch e.invoice i
             where i.customer.id = :customerId
             order by e.sentAt desc, e.id desc
            """)
    List<Email> findByCustomerInvoices(@Param("customerId") Long customerId);
}
