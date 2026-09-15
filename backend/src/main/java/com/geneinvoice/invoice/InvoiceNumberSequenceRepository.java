package com.geneinvoice.invoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvoiceNumberSequenceRepository extends JpaRepository<InvoiceNumberSequence, Long> {

    /** Reads the row with SELECT ... FOR UPDATE; it stays locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceNumberSequence s where s.id = :id")
    Optional<InvoiceNumberSequence> lockById(@Param("id") Long id);
}
