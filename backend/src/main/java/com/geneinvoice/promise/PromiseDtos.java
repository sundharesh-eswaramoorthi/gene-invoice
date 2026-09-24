package com.geneinvoice.promise;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
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

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
    public record CreatePromiseRequest(
            @NotNull Long customerId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            List<Long> invoiceIds
    ) {}

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
    public record UpdatePromiseRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            List<Long> invoiceIds
    ) {}

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
    public record OverrideStatusRequest(
            @NotNull PromiseStatus status,
            @NotNull @Size(max = FieldLimits.REASON) String reason
    ) {}

    public record CancelPromiseRequest(@Size(max = FieldLimits.REASON) String reason) {}

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
            // Trailing, in the order the blueprint fixes so B2 can append approvalPending after
            // them. Null means "not asked", the existing pocMissing convention (B1).
            Long regionId,
            String regionName,
            // Is there a change on this promise waiting for somebody to approve it? Trailing,
            // after B1's two slots, null meaning "not asked" (B2).
            Boolean approvalPending
    ) {
        /** B1's two region slots emptied, for a customer login — see InvoiceDto.withoutRegion. */
        public PromiseDto withoutRegion() {
            return new PromiseDto(id, customerId, customerName, amount, fulfilledAmount,
                    remainingAmount, promisedDate, status, statusOverridden, overrideReason,
                    overriddenByUserId, overriddenAt, collectionPoc, notes, invoices, payments,
                    createdByUserId, createdAt, updatedAt, null, null, approvalPending);
        }
    }

    // regionId(Customer) / regionName(Customer) moved onto PaymentPromise.getRegionId()/
    // getRegionName(), WHY comment and null-tolerance intact, because a mirror row has no Customer
    // to hand one and the view has to be able to answer for itself (B1, B3).

    public record PromiseSummaryDto(
            long total,
            long openCount, BigDecimal openAmount,
            long keptCount, BigDecimal keptAmount,
            long partiallyKeptCount, BigDecimal partiallyKeptAmount,
            long brokenCount, BigDecimal brokenAmount,
            long cancelledCount,
            BigDecimal promisedAmount, BigDecimal fulfilledAmount,
            // Trailing, the blueprint's fixed order for a tile. A COUNT and never an amount:
            // nothing pending has taken effect (B2).
            long awaitingApprovalCount
    ) {}
}
