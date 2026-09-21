package com.geneinvoice.promise;

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

    public record CreatePromiseRequest(
            @NotNull Long customerId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            /** Optional: when absent the customer's primary Collection POC is used. */
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            /** Optional: empty means a general promise against the account. */
            List<Long> invoiceIds,
            /**
             * Who is answerable for the promise (A1) — people, or roles at a level, picked with
             * the same tokens the email To field uses. Absent or empty seeds the list from the
             * Collection POC (A6). The count is checked by AssigneeService, which owns that limit
             * for every kind of record that has assignees.
             */
            List<EmailDtos.EmailToken> assignees
    ) {}

    public record UpdatePromiseRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @NotNull LocalDate promisedDate,
            Long collectionPocUserId,
            @Size(max = FieldLimits.PROMISE_NOTES) String notes,
            List<Long> invoiceIds,
            /**
             * The whole list, replacing what is there; an empty list clears it. Null leaves the
             * assignees alone, which is what an edit that is only about the Collection POC — the
             * inline row action and the bulk reassign — must do, or reassigning a POC would empty
             * the list beside it (A7).
             */
            List<EmailDtos.EmailToken> assignees
    ) {}

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
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            PocDtos.PocUserDto collectionPoc,
            /**
             * Everyone answerable for the promise, resolved as of this read (A1). Empty for a
             * customer-scoped caller: who internally owns a piece of work is staff identity, and
             * the list names people (AC-A8).
             */
            List<AssigneeDtos.AssigneeDto> assignees,
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
