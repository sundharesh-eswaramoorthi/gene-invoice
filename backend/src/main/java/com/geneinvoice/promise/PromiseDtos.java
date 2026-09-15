package com.geneinvoice.promise;

import com.geneinvoice.poc.PocDtos;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class PromiseDtos {

    public record CreatePromiseRequest(
            @NotNull Long customerId,
            @NotNull @Positive BigDecimal amount,
            @NotNull LocalDate promisedDate,
            /** Optional: when absent the customer's primary Collection POC is used. */
            Long collectionPocUserId,
            String notes,
            /** Optional: empty means a general promise against the account. */
            List<Long> invoiceIds
    ) {}

    public record UpdatePromiseRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            String notes,
            List<Long> invoiceIds
    ) {}

    public record OverrideStatusRequest(
            @NotNull PromiseStatus status,
            @NotNull String reason
    ) {}

    public record CancelPromiseRequest(String reason) {}

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
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            PocDtos.PocUserDto collectionPoc,
            String notes,
            List<PromiseInvoiceDto> invoices,
            List<PromisePaymentDto> payments,
            Long createdByUserId,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** Filter-aware tiles for the promises list (Feature E). */
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
