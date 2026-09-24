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

    // A held change stores this record as JSON and replays it on approval, so changing its
    // shape incompatibly — renaming or removing a component — must bump
    // PendingChange.PAYLOAD_VERSION, or a change raised under the old shape is deserialised
    // with fields silently dropped rather than refused (B2).
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
            // Trailing, in the order the blueprint fixes so B2 can append approvalPending after
            // them. Null means "not asked", the existing pocMissing convention (B1).
            Long regionId,
            String regionName,
            // Is there a change on this payment waiting for somebody to approve it? Trailing,
            // after B1's two slots. NULL means "not asked" — the pocMissing convention — so the
            // audit-snapshot sites that build a before/after blob keep their exact meaning (B2).
            Boolean approvalPending
    ) {
        /** B1's two region slots emptied, for a customer login — see InvoiceDto.withoutRegion. */
        public PaymentDto withoutRegion() {
            return new PaymentDto(id, customerId, customerName, amount, creditApplied, method,
                    notes, paidAt, status, invoices, customerCreditBalance, collectionPoc,
                    pocMissing, null, null, approvalPending);
        }

        public static PaymentDto from(Payment p) {
            return from(p, true);
        }

        public static PaymentDto from(Payment p, boolean includePoc) {
            return from(p, includePoc, null);
        }

        /** The read shape: a list or a detail GET has the flag in hand and passes it (B2). */
        public static PaymentDto from(Payment p, boolean includePoc, Boolean approvalPending) {
            return from(p, p.getAllocations().stream().map(PaidInvoiceDto::from).toList(),
                    p.getCustomer().getCreditBalance(), includePoc, approvalPending);
        }

        /**
         * The root-agnostic shape: everything flat comes off the view, and the two things a mirror
         * row cannot answer for itself are handed in. An as-of payment's allocations are the
         * allocation-mirror rows in force on the date asked and its credit balance is the customer
         * mirror's, so an as-of read passes both rather than this factory pretending the payment
         * was allocated to nothing (B3).
         */
        public static PaymentDto from(PaymentView p, List<PaidInvoiceDto> invoices,
                                      BigDecimal customerCreditBalance, boolean includePoc,
                                      Boolean approvalPending) {
            return new PaymentDto(
                    p.getId(),
                    p.getCustomerId(),
                    p.getCustomerName(),
                    p.getAmount(),
                    p.getCreditApplied(),
                    p.getMethod(),
                    p.getNotes(),
                    p.getPaidAt(),
                    p.getStatus(),
                    invoices,
                    customerCreditBalance,
                    includePoc ? PocDtos.PocUserDto.from(p.getCollectionPoc()) : null,
                    includePoc ? p.getCollectionPoc() == null : null,
                    p.getRegionId(), p.getRegionName(),
                    approvalPending
            );
        }
    }

    // regionId(Customer) / regionName(Customer) moved onto Payment.getRegionId()/getRegionName(),
    // WHY comment and null-tolerance intact, because a mirror row has no Customer to hand one and
    // the view has to be able to answer for itself (B1, B3).

    public record PaymentSummaryTiles(
            long count,
            BigDecimal totalCollected,
            BigDecimal creditApplied,
            long activeCount,
            long voidedCount,
            long pocMissingCount,
            // Trailing, the blueprint's fixed order for a tile. A COUNT and never an amount: a
            // held void has moved no money, so netting it into totalCollected would understate
            // what was actually collected (B2).
            long awaitingApprovalCount
    ) {}
}
