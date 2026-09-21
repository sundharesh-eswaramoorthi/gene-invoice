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

    List<CustomerPoc> findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc(Long customerId, PocType pocType);

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
