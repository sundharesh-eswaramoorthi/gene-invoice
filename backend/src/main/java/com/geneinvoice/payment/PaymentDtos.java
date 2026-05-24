package com.geneinvoice.payment;

import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class PaymentDtos {

    public record CreatePaymentRequest(
            @NotNull Long customerId,
            @NotNull @Positive BigDecimal amount,
            String method,
            String notes,
            List<Long> invoiceIds
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
            BigDecimal customerCreditBalance
    ) {
        public static PaymentDto from(Payment p) {
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
                    p.getCustomer().getCreditBalance()
            );
        }
    }
}
