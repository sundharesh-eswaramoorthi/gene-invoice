package com.geneinvoice.payment;

import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.poc.PocDtos;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class PaymentDtos {

    public record CreatePaymentRequest(
            @NotNull Long customerId,
            @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
            BigDecimal amount,
            @Size(max = FieldLimits.PAYMENT_METHOD) String method,
            @Size(max = FieldLimits.PAYMENT_NOTES) String notes,
            List<Long> invoiceIds,
            /** Mandatory on create; enforced in the service (AC-A2). */
            Long collectionPocUserId,
            /** Optional promises this payment should be counted against (US-B3). */
            List<Long> promiseIds
    ) {}

    /** Inline edit from the detail screen: notes and the Collection POC. */
    public record UpdatePaymentRequest(
            @Size(max = FieldLimits.PAYMENT_NOTES) String notes,
            Long collectionPocUserId
    ) {}

    public record PaidInvoiceDto(Long id, String invoiceNumber, BigDecimal total,
                                 BigDecimal paidAmount, BigDecimal balance, InvoiceStatus status,
                                 BigDecimal allocatedAmount) {
        public static PaidInvoiceDto from(PaymentAllocation a) {
            Invoice inv = a.getInvoice();
            return new PaidInvoiceDto(inv.getId(), inv.getInvoiceNumber(), inv.getTotal(),
                    inv.getPaidAmount(), inv.getBalance(), inv.getStatus(), a.getAmount());
        }
    }

    public record PaymentDto(
            Long id, Long customerId, String customerName,
            BigDecimal amount, BigDecimal creditApplied, String method, String notes,
            Instant paidAt, PaymentStatus status, List<PaidInvoiceDto> invoices,
            BigDecimal customerCreditBalance,
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            PocDtos.PocUserDto collectionPoc,
            Boolean pocMissing
    ) {
        public static PaymentDto from(Payment p) {
            return from(p, true);
        }

        public static PaymentDto from(Payment p, boolean includePoc) {
            return new PaymentDto(
                    p.getId(),
                    p.getCustomer().getId(),
                    p.getCustomer().getName(),
                    p.getAmount(),
                    p.getCreditApplied(),
                    p.getMethod(),
                    p.getNotes(),
                    p.getPaidAt(),
                    p.getStatus(),
                    p.getAllocations().stream().map(PaidInvoiceDto::from).toList(),
                    p.getCustomer().getCreditBalance(),
                    includePoc ? PocDtos.PocUserDto.from(p.getCollectionPoc()) : null,
                    includePoc ? p.getCollectionPoc() == null : null
            );
        }
    }

    /** Filter-aware tiles for the payments list (Feature E). */
    public record PaymentSummaryTiles(
            long count,
            BigDecimal totalCollected,
            BigDecimal creditApplied,
            long activeCount,
            long voidedCount,
            long pocMissingCount
    ) {}
}
