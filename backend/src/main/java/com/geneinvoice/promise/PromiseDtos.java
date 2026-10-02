package com.geneinvoice.promise;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.poc.PocDtos;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class PromiseDtos {

    /**
     * {@code assignees} (co-assignees, in the email "role at a level" token shape) is optional: they are assigned
     * once the promise is made, and kept with a held create so they are assigned when it is approved (§5.10.12).
     */
    public record CreatePromiseRequest(
            @NotNull Long customerId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            List<Long> invoiceIds,
            List<EmailDtos.EmailToken> assignees
    ) {
        @JsonCreator
        public CreatePromiseRequest {
        }

        public CreatePromiseRequest(Long customerId, BigDecimal amount, LocalDate promisedDate,
                                    Long collectionPocUserId, String notes, List<Long> invoiceIds) {
            this(customerId, amount, promisedDate, collectionPocUserId, notes, invoiceIds, null);
        }
    }

    public record UpdatePromiseRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            List<Long> invoiceIds
    ) {}

    public record OverrideStatusRequest(
            @NotNull PromiseStatus status,
            @NotNull @Size(max = FieldLimits.REASON) String reason
    ) {}

    public record CancelPromiseRequest(@Size(max = FieldLimits.REASON) String reason) {}

    /** {@code PUT /api/promises/{id}/assignees}: the promise's co-assignees, all of them (§6.13). */
    public record AssigneesRequest(List<EmailDtos.EmailToken> assignees) {}

    public record PromiseInvoiceDto(Long id, String invoiceNumber, BigDecimal total,
                                    BigDecimal balance, String status) {}

    public record PromisePaymentDto(Long id, BigDecimal amount, Instant paidAt,
                                    String method, String status) {}

    public record PromiseDto(
            Long id,
            Long customerId,
            String customerName,
            BigDecimal amount,
            BigDecimal fulfilledAmount,
            BigDecimal remainingAmount,
            LocalDate promisedDate,
            PromiseStatus status,
            boolean statusOverridden,
            String overrideReason,
            Long overriddenByUserId,
            Instant overriddenAt,
            PocDtos.PocUserDto collectionPoc,
            String notes,
            List<PromiseInvoiceDto> invoices,
            List<PromisePaymentDto> payments,
            Long createdByUserId,
            Instant createdAt,
            Instant updatedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long regionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canManage,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canOverride,
            /** Whether a change waits for approval on it; filled by the list only (§5.11.9). */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean pendingApproval,
            /** The co-assignees, with the people each means; the detail only, and never for a customer login. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<AssigneeDtos.AssigneeDto> assignees
    ) {
        public PromiseDto withPendingApproval(Boolean pending) {
            return new PromiseDto(id, customerId, customerName, amount, fulfilledAmount, remainingAmount, promisedDate,
                    status, statusOverridden, overrideReason, overriddenByUserId, overriddenAt, collectionPoc, notes,
                    invoices, payments, createdByUserId, createdAt, updatedAt, regionId, regionCode, regionName,
                    canManage, canOverride, pending, assignees);
        }

        public PromiseDto withAssignees(List<AssigneeDtos.AssigneeDto> people) {
            return new PromiseDto(id, customerId, customerName, amount, fulfilledAmount, remainingAmount, promisedDate,
                    status, statusOverridden, overrideReason, overriddenByUserId, overriddenAt, collectionPoc, notes,
                    invoices, payments, createdByUserId, createdAt, updatedAt, regionId, regionCode, regionName,
                    canManage, canOverride, pendingApproval, people);
        }
    }

    public record PromiseSummaryDto(
            long total,
            long openCount, BigDecimal openAmount,
            long keptCount, BigDecimal keptAmount,
            long partiallyKeptCount, BigDecimal partiallyKeptAmount,
            long brokenCount, BigDecimal brokenAmount,
            long cancelledCount,
            BigDecimal promisedAmount, BigDecimal fulfilledAmount
    ) {}
}
