package com.geneinvoice.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    /** The allocations currently standing against an invoice, each with its payment loaded. */
    @Query("select a from PaymentAllocation a join fetch a.payment where a.invoice.id = :invoiceId")
    List<PaymentAllocation> findByInvoiceIdWithPayment(@Param("invoiceId") Long invoiceId);

    /** The allocations standing against any of a customer's invoices, each with its payment loaded. */
    @Query("select a from PaymentAllocation a join fetch a.payment join fetch a.invoice "
            + "where a.invoice.customer.id = :customerId")
    List<PaymentAllocation> findByCustomerIdWithPayment(@Param("customerId") Long customerId);
}
