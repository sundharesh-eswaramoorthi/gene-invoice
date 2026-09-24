package com.geneinvoice.region;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRegionHistoryRepository extends JpaRepository<CustomerRegionHistory, Long> {

    /**
     * The one placement with no end date. Optional rather than a list because exactly one open row
     * per customer is the invariant the custody service holds under the customer row lock (B1).
     */
    @Query("select h from CustomerRegionHistory h where h.customerId = :customerId and h.validTo is null")
    Optional<CustomerRegionHistory> findOpen(@Param("customerId") Long customerId);

    List<CustomerRegionHistory> findByCustomerIdOrderByValidFromAsc(Long customerId);

    /**
     * The whole interval chain of one account, for the hand-written cascade in
     * CustomerService.delete. customer_id is a plain Long with no @ManyToOne, so there is no
     * foreign key for the database to cascade and an open row — one asserting in the present
     * tense that an account nobody can find is in a branch — would otherwise outlive the account
     * for ever (B1, CP-04).
     */
    void deleteByCustomerId(Long customerId);
}
