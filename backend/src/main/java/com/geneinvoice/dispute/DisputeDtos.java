package com.geneinvoice.dispute;

import com.geneinvoice.common.FieldLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public class DisputeDtos {

    public record CreateDisputeRequest(
            @NotNull DisputeTargetType targetType,
            @NotNull Long targetId,
            @NotBlank @Size(max = FieldLimits.DISPUTE_TEXT) String reason,
            String proposedChangeJson
    ) {}

    public record ResolveDisputeRequest(
            @Size(max = FieldLimits.DISPUTE_TEXT) String adminNotes,
            String appliedChangeJson
    ) {}

    /**
     * approvalPending is the conventions' fixed trailing slot for a row DTO, and null means "not
     * asked" — the pocMissing convention — so the DISPUTE_OPENED and DISPUTE_APPROVED audit
     * snapshots do not vary with an unrelated pending row. B1's regionId/regionName slots are
     * absent here by decision: R5 gave the DISPUTES table its region as ColumnDefs only and never
     * touched this record (B2, B1).
     */
    public record DisputeDto(
            Long id, Long customerId, String customerName, Long openedByUserId,
            DisputeTargetType targetType, Long targetId, String targetSummary,
            String targetNumber,
            BigDecimal targetAmount,
            String reason, String proposedChangeJson,
            DisputeStatus status, String adminNotes,
            Long resolvedByUserId, Instant resolvedAt,
            Instant createdAt, Instant updatedAt,
            Boolean approvalPending
    ) {}
}
