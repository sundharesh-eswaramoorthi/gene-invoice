package com.geneinvoice.dispute;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    public record ResolveDisputeRequest(
            @Size(max = FieldLimits.DISPUTE_TEXT) String adminNotes,
            String appliedChangeJson
    ) {}

    /** {@code PUT /api/disputes/{id}/assignees}: the dispute's assignees, all of them (§6.13). */
    public record AssigneesRequest(List<EmailDtos.EmailToken> assignees) {}

    public record DisputeDto(
            Long id, Long customerId, String customerName, Long openedByUserId,
            DisputeTargetType targetType, Long targetId, String targetSummary,
            String targetNumber,
            BigDecimal targetAmount,
            String reason, String proposedChangeJson,
            DisputeStatus status, String adminNotes,
            Long resolvedByUserId, Instant resolvedAt,
            Instant createdAt, Instant updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long regionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canResolve,
            /** Whether a change waits for approval on it; filled by the list only (§5.11.9). */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean pendingApproval,
            /** Raised by a rule for staff to review; a customer login never sees one (§5.10.11). */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean internal,
            @JsonInclude(JsonInclude.Include.NON_NULL) String origin,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long ruleId,
            /** Who works it, with the people each means; the detail only, and never for a customer login. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<AssigneeDtos.AssigneeDto> assignees
    ) {
        public DisputeDto withPendingApproval(Boolean pending) {
            return new DisputeDto(id, customerId, customerName, openedByUserId, targetType, targetId, targetSummary,
                    targetNumber, targetAmount, reason, proposedChangeJson, status, adminNotes, resolvedByUserId,
                    resolvedAt, createdAt, updatedAt, regionId, regionCode, regionName, canResolve, pending, internal,
                    origin, ruleId, assignees);
        }

        public DisputeDto withAssignees(List<AssigneeDtos.AssigneeDto> people) {
            return new DisputeDto(id, customerId, customerName, openedByUserId, targetType, targetId, targetSummary,
                    targetNumber, targetAmount, reason, proposedChangeJson, status, adminNotes, resolvedByUserId,
                    resolvedAt, createdAt, updatedAt, regionId, regionCode, regionName, canResolve, pendingApproval,
                    internal, origin, ruleId, people);
        }
    }
}
