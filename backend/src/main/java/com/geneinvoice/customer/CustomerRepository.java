package com.geneinvoice.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * The customer, locked until the transaction ends. It is the first lock every money path
     * takes — before any invoice of theirs — so two people recording a payment for one customer
     * serialise rather than both reading the same credit balance and each writing their own
     * total over the other's (PPD-01), and so no two of them can deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    /**
     * Whether another customer already has this email. The login a customer signs in with shares
     * it and user emails are unique, but a customer can outlive its login, and the freed address
     * must not then be reusable by a second customer: every email to either would go to the same
     * inbox (CP-05).
     */
    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);
}
