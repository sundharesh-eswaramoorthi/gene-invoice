package com.geneinvoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);
    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);
    long countByInvoiceNumberStartingWith(String prefix);

    boolean existsBySalesPocId(Long userId);
    long countBySalesPocId(Long userId);

    /** Outstanding balance per customer, for a page of customer rows. */
    @Query("""
            select i.customer.id, coalesce(sum(i.total - i.paidAmount), 0)
              from Invoice i
             where i.customer.id in :ids
               and i.status <> com.geneinvoice.invoice.InvoiceStatus.CANCELLED
             group by i.customer.id
            """)
    List<Object[]> sumOutstandingByCustomer(@Param("ids") Collection<Long> ids);
}
