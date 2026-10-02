package com.geneinvoice.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
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
            Long collectionPocUserId,
            List<Long> promiseIds
    ) {}

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
            PocDtos.PocUserDto collectionPoc,
            Boolean pocMissing,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long regionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canManage,
            /** Whether a change waits for approval on it; filled by the list only (§5.11.9). */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean pendingApproval
    ) {
        /** An audit snapshot: no capability flags, so a caller's rights never land in audit JSON. */
        public static PaymentDto from(Payment p) {
            return from(p, true, null);
        }

        public static PaymentDto from(Payment p, boolean includePoc) {
            return from(p, includePoc, null);
        }

        public static PaymentDto from(Payment p, boolean includePoc, RecordAccess access) {
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
                    includePoc ? p.getCollectionPoc() == null : null,
                    Region.idOf(p.getRegion()),
                    Region.codeOf(p.getRegion()),
                    Region.nameOf(p.getRegion()),
                    access == null ? null : access.canManage(ScopeResolver.Kind.PAYMENT, Region.idOf(p.getRegion())),
                    null
            );
        }

        public PaymentDto withPendingApproval(Boolean pending) {
            return new PaymentDto(id, customerId, customerName, amount, creditApplied, method, notes, paidAt, status,
                    invoices, customerCreditBalance, collectionPoc, pocMissing, regionId, regionCode, regionName,
                    canManage, pending);
        }
    }

    public record PaymentSummaryTiles(
            long count,
            BigDecimal totalCollected,
            BigDecimal creditApplied,
            long activeCount,
            long voidedCount,
            long pocMissingCount
    ) {}
}
