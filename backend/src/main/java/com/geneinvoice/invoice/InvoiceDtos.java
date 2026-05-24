package com.geneinvoice.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class InvoiceDtos {

    public record CreateInvoiceRequest(
            @NotNull Long customerId,
            Instant invoiceDate,
            String notes,
            @NotEmpty @Valid List<LineInput> items
    ) {}

    public record LineInput(
            @NotNull Long productId,
            @Positive int quantity,
            BigDecimal unitPrice
    ) {}

    public record InvoiceLineDto(Long id, Long productId, String productName,
                                 int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        public static InvoiceLineDto from(InvoiceItem it) {
            return new InvoiceLineDto(it.getId(), it.getProduct().getId(), it.getProduct().getName(),
                    it.getQuantity(), it.getUnitPrice(), it.getLineTotal());
        }
    }

    public record InvoiceDto(
            Long id, String invoiceNumber, Long customerId, String customerName,
            Instant invoiceDate, BigDecimal total, BigDecimal paidAmount, BigDecimal balance,
            InvoiceStatus status, String notes, List<InvoiceLineDto> items
    ) {
        public static InvoiceDto from(Invoice inv) {
            return new InvoiceDto(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(), inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(), inv.getNotes(),
                    inv.getItems().stream().map(InvoiceLineDto::from).toList());
        }
    }

    public record InvoiceSummary(
            Long id, String invoiceNumber, Long customerId, String customerName,
            Instant invoiceDate, BigDecimal total, BigDecimal paidAmount, BigDecimal balance,
            InvoiceStatus status
    ) {
        public static InvoiceSummary from(Invoice inv) {
            return new InvoiceSummary(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(), inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus());
        }
    }
}
