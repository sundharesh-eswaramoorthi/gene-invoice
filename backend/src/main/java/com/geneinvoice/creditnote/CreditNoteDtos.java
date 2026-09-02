package com.geneinvoice.creditnote;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public class CreditNoteDtos {

    /** Caller-supplied issuance input. Amount precision and reason are validated by the command. */
    public record IssueCreditNoteRequest(
            @NotNull BigDecimal amount,
            String reason
    ) {}

    /** One retained credit note as exposed on the invoice detail. */
    public record CreditNoteDto(
            Long id, BigDecimal amount, String reason, String issuedBy,
            Instant issuedAt, CreditNoteStatus status
    ) {
        public static CreditNoteDto from(CreditNote note) {
            return new CreditNoteDto(note.getId(), note.getAmount(), note.getReason(),
                    note.getIssuedByUsername(), note.getIssuedAt(), note.getStatus());
        }
    }

    /**
     * Result of an issued or voided credit note. For issuance the note is already
     * committed when this is returned; {@code warning} is null on full success and
     * carries the delivery warning when the post-commit admin notification failed.
     */
    public record CreditNoteResponse(
            Long id, Long invoiceId, String invoiceNumber, BigDecimal amount, String reason,
            String issuedBy, Instant issuedAt, CreditNoteStatus status, String warning
    ) {
        public static CreditNoteResponse from(CreditNote note, String warning) {
            return new CreditNoteResponse(note.getId(),
                    note.getInvoice().getId(), note.getInvoice().getInvoiceNumber(),
                    note.getAmount(), note.getReason(), note.getIssuedByUsername(),
                    note.getIssuedAt(), note.getStatus(), warning);
        }

        public CreditNoteResponse withWarning(String newWarning) {
            return new CreditNoteResponse(id, invoiceId, invoiceNumber, amount, reason,
                    issuedBy, issuedAt, status, newWarning);
        }
    }
}
