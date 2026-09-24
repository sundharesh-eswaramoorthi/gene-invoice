package com.geneinvoice.poc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The mirror's plain repository, plus the one read that answers "who sat on this account THEN" —
 * the question GET /api/customers/{id}/pocs is asked under {@code ?asOf}, and the same question
 * the customer list's two seat lists answer per row (B3).
 */
public interface CustomerPocHistoryRepository extends JpaRepository<CustomerPocHistory, Long> {

    /**
     * The seats held on these accounts at {@code at}, in the live finder's order — type, then the
     * primary first, then oldest first — so an as-of seat list and a live seat list read the same
     * way down the page (B3, CP-02).
     */
    @Query("""
            select s from CustomerPocHistory s
             where s.customerId in :customerIds
               and s.validFrom <= :at
               and s.validTo > :at
               and s.deleted = false
             order by s.pocType asc, s.primary desc, s.id asc
            """)
    List<CustomerPocHistory> inForce(@Param("customerIds") Collection<Long> customerIds,
                                     @Param("at") Instant at);
}
