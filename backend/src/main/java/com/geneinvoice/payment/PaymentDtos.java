package com.geneinvoice.payment;

import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.InvoiceService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

public class PaymentDtos {

    public record CreatePaymentRequest(
            @NotNull Long customerId,
            @NotNull @Positive BigDecimal amount,
            String method,
            String notes,
            List<Long> invoiceIds
    ) {}

    public record PaidInvoiceDto(Long id, String invoiceNumber, BigDecimal total,
                                 BigDecimal paidAmount, BigDecimal creditedAmount, BigDecimal balance,
                                 InvoiceStatus status, BigDecimal allocatedAmount) {
        public static PaidInvoiceDto from(PaymentAllocation a, BigDecimal credited) {
            Invoice inv = a.getInvoice();
            return new PaidInvoiceDto(inv.getId(), inv.getInvoiceNumber(), inv.getTotal(),
                    inv.getPaidAmount(), credited, InvoiceService.outstandingOf(inv, credited),
                    inv.getStatus(), a.getAmount());
        }
    }

    public record PaymentDto(
            Long id, Long customerId, String customerName,
            BigDecimal amount, BigDecimal creditApplied, String method, String notes,
            Instant paidAt, PaymentStatus status, List<PaidInvoiceDto> invoices,
            BigDecimal customerCreditBalance
    ) {
        /** @param creditedByInvoiceId resolves the active (non-voided) credited amount per invoice. */
        public static PaymentDto from(Payment p, Function<Long, BigDecimal> creditedByInvoiceId) {
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
                    p.getAllocations().stream()
                            .map(a -> PaidInvoiceDto.from(a, creditedByInvoiceId.apply(a.getInvoice().getId())))
                            .toList(),
                    p.getCustomer().getCreditBalance()
            );
        }
    }
}
