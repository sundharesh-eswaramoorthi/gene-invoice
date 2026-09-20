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

    /**
     * The invoice, locked until the transaction ends, for a caller about to change what it has
     * been paid. Every writer of {@code paidAmount} takes this lock, so two payments landing on
     * one invoice at the same moment queue instead of reading the same figure and each adding to
     * it — which loses one of them outright (PPD-01).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    /**
     * The same lock over a set of invoices, always taken in ascending id order — with the
     * customer's own lock taken first — so two payments that touch overlapping invoices can never
     * hold half of each other's rows and deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id in :ids order by i.id")
    List<Invoice> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /** Every invoice of one customer, locked in ascending id order, for the same reason. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.customer.id = :customerId order by i.id")
    List<Invoice> findByCustomerIdForUpdate(@Param("customerId") Long customerId);
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

    /** The part of that balance which is past its due date, for the customer detail (AC-A6). */
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
