package com.geneinvoice.promise;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentPromiseRepository extends JpaRepository<PaymentPromise, Long> {

    List<PaymentPromise> findByCustomerIdOrderByPromisedDateDesc(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentPromise p where p.id = :id")
    Optional<PaymentPromise> findByIdForUpdate(@Param("id") Long id);

    /** The customer alone, read without a lock: what a write locks first, before the promise (§5.11.5). */
    @Query("select p.customer.id from PaymentPromise p where p.id = :id")
    Optional<Long> customerIdOf(@Param("id") Long id);

    @Query("select p from PaymentPromise p where p.customer.id = :customerId "
            + "and p.status <> com.geneinvoice.promise.PromiseStatus.CANCELLED")
    List<PaymentPromise> findLiveByCustomer(@Param("customerId") Long customerId);

    @Query("select distinct p.customer.id from PaymentPromise p "
            + "where p.status <> com.geneinvoice.promise.PromiseStatus.CANCELLED and p.region.id in :regionIds "
            + "order by p.customer.id")
    List<Long> findCustomerIdsWithLivePromisesInRegions(@Param("regionIds") Collection<Long> regionIds);

    @Query("select distinct p.customer.id from PaymentPromise p "
            + "where p.status <> com.geneinvoice.promise.PromiseStatus.CANCELLED and p.region.id in :regionIds "
            + "and p.collectionPoc.id = :pocId order by p.customer.id")
    List<Long> findCustomerIdsWithLivePromisesOf(@Param("regionIds") Collection<Long> regionIds,
                                                 @Param("pocId") Long pocId);

    @Query("select distinct p.customer.id from PaymentPromise p where p.status = com.geneinvoice.promise.PromiseStatus.OPEN "
            + "and p.statusOverridden = false and p.promisedDate < :today order by p.customer.id")
    List<Long> findCustomerIdsWithOverdueOpen(@Param("today") LocalDate today);

    @Query("select distinct p from PaymentPromise p join p.invoices i where i.id = :invoiceId")
    List<PaymentPromise> findByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("select distinct p from PaymentPromise p join p.payments pay where pay.id = :paymentId")
    List<PaymentPromise> findByPaymentId(@Param("paymentId") Long paymentId);

    @Query("select count(pay) from PaymentPromise p join p.payments pay where p.id = :promiseId")
    long countLinkedPayments(@Param("promiseId") Long promiseId);

    long countByCollectionPocId(Long userId);

    boolean existsByCollectionPocId(Long userId);
}
