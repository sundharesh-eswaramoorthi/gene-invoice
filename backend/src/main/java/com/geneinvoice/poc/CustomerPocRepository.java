package com.geneinvoice.poc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerPocRepository extends JpaRepository<CustomerPoc, Long> {

    List<CustomerPoc> findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(Long customerId);

    List<CustomerPoc> findByCustomerIdAndPocType(Long customerId, PocType pocType);

    Optional<CustomerPoc> findByCustomerIdAndPocTypeAndPrimaryTrue(Long customerId, PocType pocType);

    Optional<CustomerPoc> findByCustomerIdAndUserIdAndPocType(Long customerId, Long userId, PocType pocType);

    List<CustomerPoc> findByCustomerIdIn(Collection<Long> customerIds);

    boolean existsByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByCustomerId(Long customerId);
}
