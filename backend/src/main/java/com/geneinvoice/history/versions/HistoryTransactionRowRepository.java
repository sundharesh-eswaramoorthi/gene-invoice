package com.geneinvoice.history.versions;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** When each history transaction that published automation events happened. */
public interface HistoryTransactionRowRepository extends JpaRepository<HistoryTransactionRow, Long> {

    Optional<HistoryTransactionRow> findFirstByTxId(String txId);

    /** Rows older than the events kept for automation (§5.10.8): nothing evaluates at those moments any more. */
    @Query("select h.id from HistoryTransactionRow h where h.instant < :before order by h.id")
    List<Long> findIdsBefore(@Param("before") Instant before, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from HistoryTransactionRow h where h.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
