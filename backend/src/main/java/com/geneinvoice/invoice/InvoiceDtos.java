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
            /** An override; without it the due date comes from the customer's terms (§2.2). */
            LocalDate dueDate,
            /** The terms to date this invoice by; {@code CUSTOM} needs a {@code dueDate}. */
            PaymentTerm paymentTerm,
            @Size(max = FieldLimits.INVOICE_NOTES) String notes,
            /** Mandatory on create; enforced in the service so the rule holds for every caller. */
            Long salesPocUserId,
            @NotEmpty @Valid List<LineInput> items
    ) {
        /** The request as it was before due dates, for callers that take the customer's terms. */
        public CreateInvoiceRequest(Long customerId, Instant invoiceDate, String notes,
                                    Long salesPocUserId, List<LineInput> items) {
            this(customerId, invoiceDate, null, null, notes, salesPocUserId, items);
        }
    }

    /** Inline edit of the fields the detail screen exposes on an existing invoice. */
    public record UpdateInvoiceRequest(
            @Size(max = FieldLimits.INVOICE_NOTES) String notes,
            Long salesPocUserId,
            /** Set on its own, this is an override and the terms become {@code CUSTOM}. */
            LocalDate dueDate,
            /** Set on its own, the due date is recomputed from the invoice date. */
            PaymentTerm paymentTerm,
            /**
             * The version the editor had in front of them, from the invoice they loaded. Sent
             * back, a save that lost the race is refused with a 409 rather than silently
             * discarding the one that got there first (UI-09). Omitted, there is no precondition,
             * which is what a caller that never read a version gets.
             */
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
            /** Null for a customer-scoped caller, who never sees POC identity (AC-A8). */
            PocDtos.PocUserDto salesPoc,
            /** True when this record predates the POC field and still has none (AC-A9). */
            Boolean pocMissing,
            /**
             * The row version as this reader saw it. An editor sends it back with its save so a
             * save composed against an older version is refused instead of landing on top of
             * whatever has happened since (UI-09).
             */
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
            long pocMissingCount,
            /** Over the whole filtered set, like every other tile (AC-A7). */
            BigDecimal overdueAmount,
            long overdueCount
    ) {}

    /**
     * What the invoice form shows the moment a customer is picked: the date their terms give, and
     * whether those terms are the customer's own or the system default (US-A2).
     */
    public record DueDatePreview(LocalDate dueDate, PaymentTerm paymentTerm, String paymentTermLabel,
                                 TermSource source) {}

    public enum TermSource { CUSTOMER, DEFAULT }

    static String label(PaymentTerm term) {
        return term == null ? null : term.label();
    }
}
