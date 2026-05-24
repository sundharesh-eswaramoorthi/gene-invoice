package com.geneinvoice.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class DisputeDtos {

    public record CreateDisputeRequest(
            @NotNull DisputeTargetType targetType,
            @NotNull Long targetId,
            @NotBlank String reason,
            String proposedChangeJson
    ) {}

    public record ResolveDisputeRequest(
            String adminNotes,
            String appliedChangeJson
    ) {}

    public record DisputeDto(
            Long id, Long customerId, String customerName, Long openedByUserId,
            DisputeTargetType targetType, Long targetId, String targetSummary,
            String reason, String proposedChangeJson,
            DisputeStatus status, String adminNotes,
            Long resolvedByUserId, Instant resolvedAt,
            Instant createdAt, Instant updatedAt
    ) {}
}
