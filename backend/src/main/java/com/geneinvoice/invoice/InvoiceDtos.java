package com.geneinvoice.invoice;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.Money;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.product.Product;
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

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
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

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
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

        /**
         * The line as it stood on the date being answered. The product's NAME is today's, because
         * the catalogue is not mirrored — contract clause a.3, the same rule the Sales POC's name
         * and the region's name already follow — and the mirror keeps a read-only association to
         * it so no extra batching is needed. Null-tolerant on the product, which a live line never
         * is: a mirror row must outlive the product it names and carries no foreign key to it, so
         * a catalogue entry deleted since is a null here rather than a broken page (B3).
         */
        public static InvoiceLineDto from(InvoiceItemHistory it) {
            Product product = it.getProduct();
            return new InvoiceLineDto(it.getId(), it.getProductId(),
                    product == null ? null : product.getName(),
                    it.getQuantity() == null ? 0 : it.getQuantity(),
                    it.getUnitPrice(), it.getLineTotal());
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
            Instant createdAt,
            // Trailing, and in the order the blueprint fixes so that B2's approvalPending can be
            // appended after them without anybody having to agree twice. Null means "not asked",
            // the existing pocMissing convention (B1).
            Long regionId,
            String regionName,
            // Is there a change on this invoice waiting for somebody to approve it? Trailing,
            // after B1's two slots, in the order the blueprint fixes. NULL means "not asked" —
            // the pocMissing convention — which is what keeps the ~5 audit-snapshot sites in
            // InvoiceService unchanged in meaning: a before/after blob must not vary because
            // somebody raised an unrelated change this morning (B2).
            Boolean approvalPending
    ) {
        /**
         * THE SAME ROW WITH B1'S TWO REGION SLOTS EMPTIED, for a customer login (B1, AUTH-08).
         *
         * <p>Null is already this record's word for "not asked" — the pocMissing convention — so
         * this adds no third state and no new shape on the wire. Applied at the READ edge and
         * never inside the factory, because the factory also builds the before/after blobs the
         * audit trail stores, and a trail whose contents depended on who happened to be reading
         * would be worth nothing. {@link com.geneinvoice.poc.ScopeResolver#canSeeRegion} says who,
         * and TableSchema.visibleTo strikes the same two columns from the schema so the payload
         * and the column list agree (B1, AUTH-08).
         */
        public InvoiceDto withoutRegion() {
            return new InvoiceDto(id, invoiceNumber, customerId, customerName, invoiceDate,
                    dueDate, paymentTerm, paymentTermLabel, overdue, daysOverdue,
                    total, paidAmount, balance, status, notes, items, salesPoc, pocMissing,
                    version, createdAt, null, null, approvalPending);
        }

        public static InvoiceDto from(Invoice inv) {
            return from(inv, true);
        }

        public static InvoiceDto from(Invoice inv, boolean includePoc) {
            return from(inv, includePoc, null);
        }

        /** The read shape: a list or a detail GET has the flag in hand and passes it (B2). */
        public static InvoiceDto from(Invoice inv, boolean includePoc, Boolean approvalPending) {
            return from(inv, inv.getItems().stream().map(InvoiceLineDto::from).toList(),
                    inv.getVersion(), includePoc, approvalPending);
        }

        /**
         * The root-agnostic shape: everything flat comes off the view, and the two things a mirror
         * row cannot answer for itself are handed in. The line items of an as-of invoice are the
         * item-mirror rows in force on the date asked, and a past snapshot has no live row version
         * to lock against — so an as-of read passes its own items and a null version rather than
         * this factory pretending either is empty (B3).
         */
        public static InvoiceDto from(InvoiceView inv, List<InvoiceLineDto> items, Long version,
                                      boolean includePoc, Boolean approvalPending) {
            LocalDate today = InvoiceDates.today();
            return new InvoiceDto(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomerId(), inv.getCustomerName(),
                    inv.getInvoiceDate(),
                    inv.getDueDate(), inv.getPaymentTerm(), label(inv.getPaymentTerm()),
                    inv.isOverdue(today), inv.daysOverdue(today),
                    inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(), inv.getNotes(),
                    items,
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null,
                    version,
                    inv.getCreatedAt(),
                    inv.getRegionId(), inv.getRegionName(),
                    approvalPending);
        }

        /**
         * THE ONE FAT FACTORY THE DESIGN ALLOWS: an invoice as it stood on a date, with the LINES
         * it had then (B3).
         *
         * <p>Five lines rather than twenty-five, because B3-VIEWS already hoisted the two things a
         * mirror cannot answer for itself into parameters: this one supplies the item-mirror rows
         * in force at the same instant, and a null version. NULL IS THE MEANING, not a gap — a
         * past snapshot has no live row version to lock an edit against, and a number here would
         * invite a client to send it back on a PATCH and have it accepted against whatever the
         * record has become since.
         *
         * <p>{@code regionId}/{@code regionName} are already on the row: the slice fills the
         * mirror's two {@code @Transient} fields from RegionPlacements before calling this, which
         * is why the signature does not grow a sixth parameter and why the live factory above is
         * the one that runs (B3, B1).
         */
        public static InvoiceDto from(InvoiceHistory inv, List<InvoiceItemHistory> lines,
                                      boolean includePoc, Boolean approvalPending) {
            return from(inv, lines.stream().map(InvoiceLineDto::from).toList(), null,
                    includePoc, approvalPending);
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
            Boolean pocMissing,
            Long regionId,
            String regionName,
            // Trailing, after B1's two slots, null meaning "not asked" (B2).
            Boolean approvalPending
    ) {
        /** B1's two region slots emptied, for a customer login — see InvoiceDto.withoutRegion. */
        public InvoiceSummary withoutRegion() {
            return new InvoiceSummary(id, invoiceNumber, customerId, customerName, invoiceDate,
                    dueDate, paymentTerm, paymentTermLabel, overdue, daysOverdue,
                    total, paidAmount, balance, status, salesPoc, pocMissing,
                    null, null, approvalPending);
        }

        public static InvoiceSummary from(InvoiceView inv) {
            return from(inv, true);
        }

        public static InvoiceSummary from(InvoiceView inv, boolean includePoc) {
            return from(inv, includePoc, null);
        }

        /**
         * The read shape: page() resolves the whole page's flags in one query and passes them.
         *
         * <p>The parameter is the VIEW and not the entity, so ONE factory serves the live row and
         * the as-of mirror row and the two cannot drift apart. Source-compatible — Invoice
         * implements InvoiceView, so no call site moves (B3, B2).
         */
        public static InvoiceSummary from(InvoiceView inv, boolean includePoc,
                                          Boolean approvalPending) {
            LocalDate today = InvoiceDates.today();
            return new InvoiceSummary(inv.getId(), inv.getInvoiceNumber(),
                    inv.getCustomerId(), inv.getCustomerName(),
                    inv.getInvoiceDate(),
                    inv.getDueDate(), inv.getPaymentTerm(), label(inv.getPaymentTerm()),
                    inv.isOverdue(today), inv.daysOverdue(today),
                    inv.getTotal(), inv.getPaidAmount(), inv.getBalance(),
                    inv.getStatus(),
                    includePoc ? PocDtos.PocUserDto.from(inv.getSalesPoc()) : null,
                    includePoc ? inv.getSalesPoc() == null : null,
                    inv.getRegionId(), inv.getRegionName(),
                    approvalPending);
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
            long overdueCount,
            // Trailing, the blueprint's fixed order for a tile. A COUNT and never an amount:
            // nothing pending has taken effect, so folding it into outstanding would report
            // money that has not moved as though it had (B2).
            long awaitingApprovalCount
    ) {}

    public record DueDatePreview(LocalDate dueDate, PaymentTerm paymentTerm, String paymentTermLabel,
                                 TermSource source) {}

    public enum TermSource { CUSTOMER, DEFAULT }

    static String label(PaymentTerm term) {
        return term == null ? null : term.label();
    }

    // regionId(Customer) / regionName(Customer) moved onto Invoice.getRegionId()/getRegionName(),
    // WHY comment and null-tolerance intact, because a mirror row has no Customer to hand one and
    // the view has to be able to answer for itself (B1, B3).
}
