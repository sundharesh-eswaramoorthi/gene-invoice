package com.geneinvoice.invoice;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.poc.PocDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class InvoiceDtos {

    public record CreateInvoiceRequest(
            @NotNull Long customerId,
            Instant invoiceDate,
            LocalDate dueDate,
            PaymentTerm paymentTerm,
            @Size(max = FieldLimits.INVOICE_NOTES) String notes,
            Long salesPocUserId,
            @NotEmpty @Valid List<LineInput> items
    ) {
        public CreateInvoiceRequest(Long customerId, Instant invoiceDate, String notes,
                                    Long salesPocUserId, List<LineInput> items) {
            this(customerId, invoiceDate, null, null, notes, salesPocUserId, items);
        }
    }

    public record UpdateInvoiceRequest(
            @Size(max = FieldLimits.INVOICE_NOTES) String notes,
            Long salesPocUserId,
            LocalDate dueDate,
            PaymentTerm paymentTerm,
            Long version
    ) {
        public UpdateInvoiceRequest(String notes, Long salesPocUserId) {
            this(notes, salesPocUserId, null, null, null);
        }

        public UpdateInvoiceRequest(String notes, Long salesPocUserId, LocalDate dueDate,
                                    PaymentTerm paymentTerm) {
            this(notes, salesPocUserId, dueDate, paymentTerm, null);
        }
    }

    public record LineInput(
            @NotNull Long productId,
            @Positive int quantity,
            @PositiveOrZero @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE)
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
            Instant invoiceDate,
            LocalDate dueDate, PaymentTerm paymentTerm, String paymentTermLabel,
            /** Worked out from today's date, never stored (D3). */
            boolean overdue, int daysOverdue,
            BigDecimal total, BigDecimal paidAmount, BigDecimal balance,
            InvoiceStatus status, String notes, List<InvoiceLineDto> items,
            PocDtos.PocUserDto salesPoc,
            Boolean pocMissing,
            Long version,
            Instant createdAt
    ) {
        public static InvoiceDto from(Invoice inv) {
            return from(inv, true);
        }

        public static InvoiceDto from(Invoice inv, boolean includePoc) {
            LocalDate today = InvoiceDates.today();
            return new InvoiceDto(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(),
                    inv.getDueDate(), inv.getPaymentTerm(), label(inv.getPaymentTerm()),
                    inv.isOverdue(today), inv.daysOverdue(today),
                    inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(), inv.getNotes(),
                    inv.getItems().stream().map(InvoiceLineDto::from).toList(),
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null,
                    inv.getVersion(),
                    inv.getCreatedAt());
        }
    }

    public record InvoiceSummary(
            Long id, String invoiceNumber, Long customerId, String customerName,
            Instant invoiceDate,
            LocalDate dueDate, PaymentTerm paymentTerm, String paymentTermLabel,
            boolean overdue, int daysOverdue,
            BigDecimal total, BigDecimal paidAmount, BigDecimal balance,
            InvoiceStatus status,
            PocDtos.PocUserDto salesPoc,
            Boolean pocMissing
    ) {
        public static InvoiceSummary from(Invoice inv) {
            return from(inv, true);
        }

        public static InvoiceSummary from(Invoice inv, boolean includePoc) {
            LocalDate today = InvoiceDates.today();
            return new InvoiceSummary(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomer().getId(), inv.getCustomer().getName(),
                    inv.getInvoiceDate(),
                    inv.getDueDate(), inv.getPaymentTerm(), label(inv.getPaymentTerm()),
                    inv.isOverdue(today), inv.daysOverdue(today),
                    inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(),
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null);
        }
    }

    public record InvoiceSummaryTiles(
            long count,
            BigDecimal totalBilled,
            BigDecimal totalPaid,
            BigDecimal outstanding,
            long unpaidCount,
            long partiallyPaidCount,
            long fullyPaidCount,
            long cancelledCount,
            long pocMissingCount,
            BigDecimal overdueAmount,
            long overdueCount
    ) {}

    public record DueDatePreview(LocalDate dueDate, PaymentTerm paymentTerm, String paymentTermLabel,
                                 TermSource source) {}

    public enum TermSource { CUSTOMER, DEFAULT }

    static String label(PaymentTerm term) {
        return term == null ? null : term.label();
    }
}
