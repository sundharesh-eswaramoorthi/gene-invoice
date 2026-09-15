package com.geneinvoice.invoice;

import com.geneinvoice.poc.PocDtos;
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
            /** Mandatory on create; enforced in the service so the rule holds for every caller. */
            Long salesPocUserId,
            @NotEmpty @Valid List<LineInput> items
    ) {}

    /** Inline edit of the fields the detail screen exposes on an existing invoice. */
    public record UpdateInvoiceRequest(
            String notes,
            Long salesPocUserId
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
            InvoiceStatus status, String notes, List<InvoiceLineDto> items,
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            PocDtos.PocUserDto salesPoc,
            /** True when this record predates the POC field and still has none (AC-A9). */
            Boolean pocMissing,
            Instant createdAt
    ) {
        public static InvoiceDto from(Invoice inv) {
            return from(inv, true);
        }

        public static InvoiceDto from(Invoice inv, boolean includePoc) {
            return new InvoiceDto(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(), inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(), inv.getNotes(),
                    inv.getItems().stream().map(InvoiceLineDto::from).toList(),
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null,
                    inv.getCreatedAt());
        }
    }

    public record InvoiceSummary(
            Long id, String invoiceNumber, Long customerId, String customerName,
            Instant invoiceDate, BigDecimal total, BigDecimal paidAmount, BigDecimal balance,
            InvoiceStatus status,
            PocDtos.PocUserDto salesPoc,
            Boolean pocMissing
    ) {
        public static InvoiceSummary from(Invoice inv) {
            return from(inv, true);
        }

        public static InvoiceSummary from(Invoice inv, boolean includePoc) {
            return new InvoiceSummary(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(), inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(),
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null);
        }
    }

    /** Filter-aware tiles for the invoices list (Feature E). */
    public record InvoiceSummaryTiles(
            long count,
            BigDecimal totalBilled,
            BigDecimal totalPaid,
            BigDecimal outstanding,
            long unpaidCount,
            long partiallyPaidCount,
            long fullyPaidCount,
            long cancelledCount,
            long pocMissingCount
    ) {}
}
