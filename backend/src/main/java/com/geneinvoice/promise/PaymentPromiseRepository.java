package com.geneinvoice.promise;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PaymentPromiseRepository extends JpaRepository<PaymentPromise, Long> {

    List<PaymentPromise> findByCustomerIdOrderByPromisedDateDesc(Long customerId);

    @Query("select p from PaymentPromise p where p.customer.id = :customerId "
            + "and p.status <> com.geneinvoice.promise.PromiseStatus.CANCELLED")
    List<PaymentPromise> findLiveByCustomer(@Param("customerId") Long customerId);

    @Query("select distinct p from PaymentPromise p join p.invoices i where i.id = :invoiceId")
    List<PaymentPromise> findByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("select distinct p from PaymentPromise p join p.payments pay where pay.id = :paymentId")
    List<PaymentPromise> findByPaymentId(@Param("paymentId") Long paymentId);

    /** Promises the sweeper must look at: still open and past their date. */
    @Query("select p from PaymentPromise p where p.status = com.geneinvoice.promise.PromiseStatus.OPEN "
            + "and p.statusOverridden = false and p.promisedDate < :today")
    List<PaymentPromise> findOverdueOpen(@Param("today") LocalDate today);

    @Query("select count(pay) from PaymentPromise p join p.payments pay where p.id = :promiseId")
    long countLinkedPayments(@Param("promiseId") Long promiseId);

    long countByCollectionPocId(Long userId);

    boolean existsByCollectionPocId(Long userId);
}
