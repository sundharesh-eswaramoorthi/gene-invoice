package com.geneinvoice.history.versions;

import com.geneinvoice.history.VersionRow;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** A {@code payment_allocations} row over a period: how much of a payment went to which invoice. */
@Entity
@Table(name = "payment_allocation_versions", indexes = {
        @Index(name = "idx_payment_allocation_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_payment_allocation_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_payment_allocation_versions_payment", columnList = "payment_id,valid_to"),
        @Index(name = "idx_payment_allocation_versions_invoice", columnList = "invoice_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class PaymentAllocationVersion extends VersionRow {

    @Column(name = "payment_id")
    private Long paymentId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Payment payment;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Invoice invoice;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;
}
