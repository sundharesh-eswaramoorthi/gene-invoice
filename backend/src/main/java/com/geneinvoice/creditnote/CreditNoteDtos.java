package com.geneinvoice.creditnote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public class CreditNoteDtos {

    public record IssueCreditNoteRequest(
            @NotNull @Positive BigDecimal amount,
            @NotBlank String reason
    ) {}

    public record CreditNoteDto(
            Long id, Long invoiceId, BigDecimal amount, String reason,
            Long issuedByUserId, Instant issuedAt, CreditNoteStatus status
    ) {
        public static CreditNoteDto from(CreditNote note) {
            return new CreditNoteDto(note.getId(), note.getInvoice().getId(), note.getAmount(),
                    note.getReason(), note.getIssuedByUserId(), note.getIssuedAt(), note.getStatus());
        }
    }

    public record IssueOutcome(CreditNoteDto note, String warning) {}
}
