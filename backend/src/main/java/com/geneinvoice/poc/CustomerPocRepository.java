package com.geneinvoice.poc;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerPocRepository extends JpaRepository<CustomerPoc, Long> {

    List<CustomerPoc> findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(Long customerId);

    List<CustomerPoc> findByCustomerIdAndPocType(Long customerId, PocType pocType);

    /**
     * The seats marked primary for one kind, oldest first. A list rather than one row on purpose:
     * the invariant is "at most one", and a method that can only return one throws where a second
     * one exists — which used to make "make primary" fail with a server error for good on any
     * customer where two had slipped through (CP-02). Every caller demotes all it is given.
     */
    List<CustomerPoc> findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc(Long customerId, PocType pocType);

    /**
     * The whole seat group of one kind, locked until the transaction ends, for a caller about to
     * move "primary" within it. The caller locks the customer first, so a seat being added to an
     * empty group serialises too — there is no row to lock for one that does not exist yet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerPoc p where p.customer.id = :customerId and p.pocType = :pocType"
            + " order by p.id")
    List<CustomerPoc> findByCustomerIdAndPocTypeForUpdate(@Param("customerId") Long customerId,
                                                          @Param("pocType") PocType pocType);

    Optional<CustomerPoc> findByCustomerIdAndUserIdAndPocType(Long customerId, Long userId, PocType pocType);

    List<CustomerPoc> findByCustomerIdIn(Collection<Long> customerIds);

    boolean existsByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByCustomerId(Long customerId);
}
