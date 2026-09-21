package com.geneinvoice.invoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerIdOrderByInvoiceDateDesc(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id in :ids order by i.id")
    List<Invoice> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.customer.id = :customerId order by i.id")
    List<Invoice> findByCustomerIdForUpdate(@Param("customerId") Long customerId);
    List<Invoice> findByStatusIn(List<InvoiceStatus> statuses);
    long countByInvoiceNumberStartingWith(String prefix);

    boolean existsBySalesPocId(Long userId);
    long countBySalesPocId(Long userId);

    @Query("""
            select i.customer.id, coalesce(sum(i.total - i.paidAmount), 0)
              from Invoice i
             where i.customer.id in :ids
               and i.status <> com.geneinvoice.invoice.InvoiceStatus.CANCELLED
             group by i.customer.id
            """)
    List<Object[]> sumOutstandingByCustomer(@Param("ids") Collection<Long> ids);

    @Query("""
            select i.customer.id, coalesce(sum(i.total - i.paidAmount), 0)
              from Invoice i
             where i.customer.id in :ids
               and i.status <> com.geneinvoice.invoice.InvoiceStatus.CANCELLED
               and i.dueDate < :today
               and i.total - i.paidAmount > 0
             group by i.customer.id
            """)
    List<Object[]> sumOverdueByCustomer(@Param("ids") Collection<Long> ids,
                                        @Param("today") LocalDate today);
}
