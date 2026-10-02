package com.geneinvoice.history.versions;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.VersionRow;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An {@code invoices} row over a period, with its lines embedded as {@code items_json} (a JSON array of
 * {@link ItemSnapshot}, sorted by item id): a line change is a new version of its invoice.
 */
@Entity
@Table(name = "invoice_versions", indexes = {
        @Index(name = "idx_invoice_versions_entity", columnList = "entity_id,valid_to"),
        @Index(name = "idx_invoice_versions_window", columnList = "valid_to,valid_from"),
        @Index(name = "idx_invoice_versions_customer", columnList = "customer_id,valid_to"),
        @Index(name = "idx_invoice_versions_region", columnList = "region_id,valid_to"),
        @Index(name = "idx_invoice_versions_sales_poc", columnList = "sales_poc_user_id,valid_to")
})
@Getter
@Setter
@NoArgsConstructor
public class InvoiceVersion extends VersionRow {

    @Column(length = 40)
    private String invoiceNumber;

    @Column(name = "customer_id")
    private Long customerId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Customer customer;

    private Instant invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Convert(converter = EnumNames.PaymentTermName.class)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    @Column(precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Convert(converter = EnumNames.InvoiceStatusName.class)
    @Column(length = 30)
    private InvoiceStatus status;

    @Column(length = 500)
    private String notes;

    @Column(name = "sales_poc_user_id")
    private Long salesPocId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_poc_user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User salesPoc;

    @Column(name = "region_id")
    private Long regionId;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Region region;

    private Instant createdAt;

    @Lob
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;
}
