package com.geneinvoice.dispute;

import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.email.EmailDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class DisputeDtos {

    public record CreateDisputeRequest(
            @NotNull DisputeTargetType targetType,
            @NotNull Long targetId,
            @NotBlank @Size(max = FieldLimits.DISPUTE_TEXT) String reason,
            String proposedChangeJson
    ) {}

    /**
     * The whole assignee list, replacing whatever is there; an empty list leaves the dispute
     * assigned to nobody. It is its own request rather than a field on the form that opens a
     * dispute because a dispute is opened from the customer's side, where staff cannot be seen,
     * let alone picked (AC-A8, A1).
     */
    public record SetAssigneesRequest(@NotNull List<EmailDtos.EmailToken> assignees) {}

    public record ResolveDisputeRequest(
            @Size(max = FieldLimits.DISPUTE_TEXT) String adminNotes,
            String appliedChangeJson
    ) {}

    public record DisputeDto(
            Long id, Long customerId, String customerName, Long openedByUserId,
            DisputeTargetType targetType, Long targetId, String targetSummary,
            /** The invoice number, or "#id" for a payment; null once the record is gone. */
            String targetNumber,
            /** The invoice total or payment amount. */
            BigDecimal targetAmount,
            String reason, String proposedChangeJson,
            DisputeStatus status, String adminNotes,
            /**
             * Who is answerable for the dispute, resolved as of this read (A1). Empty for a
             * customer-scoped caller, who never learns staff identity (AC-A8).
             */
            List<AssigneeDtos.AssigneeDto> assignees,
            Long resolvedByUserId, Instant resolvedAt,
            Instant createdAt, Instant updatedAt
    ) {}
}
