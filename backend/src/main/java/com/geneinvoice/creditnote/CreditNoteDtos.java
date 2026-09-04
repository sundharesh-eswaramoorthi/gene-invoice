package com.geneinvoice.creditnote;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class CreditNoteDtos {

    public record IssueCreditNoteRequest(
            @NotNull BigDecimal amount,
            String reason
    ) {}

    public record CreditNoteDto(
            Long id, Long invoiceId, String invoiceNumber,
            BigDecimal amount, String reason,
            Long issuedByUserId, String issuedByName, Instant issuedAt,
            boolean voided
    ) {
        public static CreditNoteDto from(CreditNote n) {
            return new CreditNoteDto(n.getId(),
                    n.getInvoice().getId(), n.getInvoice().getInvoiceNumber(),
                    n.getAmount(), n.getReason(),
                    n.getIssuedByUserId(), n.getIssuedByName(), n.getIssuedAt(),
                    n.isVoided());
        }

        public static List<CreditNoteDto> fromAll(List<CreditNote> notes) {
            return notes.stream().map(CreditNoteDto::from).collect(Collectors.toList());
        }
    }

    /**
     * Result of an issuance attempt that committed. The credit note is durable regardless of
     * the notification outcome; notificationWarning/message report a post-commit admin
     * notification delivery failure as success-with-warning (never a rollback, retry or queue).
     */
    public record IssueCreditNoteResponse(
            CreditNoteDto creditNote,
            boolean notificationWarning,
            String notificationWarningMessage
    ) {}
}
