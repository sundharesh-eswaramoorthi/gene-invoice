package com.geneinvoice.creditnote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {
    List<CreditNote> findByInvoiceIdOrderByIssuedAtDesc(Long invoiceId);
}
