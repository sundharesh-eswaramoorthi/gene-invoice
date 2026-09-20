package com.geneinvoice.invoice;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 40)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false)
    private Instant invoiceDate;

    /**
     * When the money is due — a calendar fact, not a moment. Mapped nullable so {@code ddl-auto:
     * update} can add the column to a table that already has rows; {@link InvoiceSchemaUpgrade}
     * fills those in and makes it not null. Every invoice has one (AC-A1).
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** The terms the due date came from, {@code CUSTOM} when someone chose the date themselves. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.UNPAID;

    @Column(length = 500)
    private String notes;

    /**
     * The salesperson who owns this invoice. Mandatory on create; stays null on invoices that
     * predate the field, which surface a "POC missing" badge instead of being blocked.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_poc_user_id")
    private User salesPoc;

    /**
     * Guards {@code paidAmount} against a writer that did not take the row lock the money path
     * takes ({@link InvoiceRepository#findByIdForUpdate}): the update then fails loudly and the
     * caller is told to reload, instead of silently overwriting what the other one paid (PPD-01).
     * Mapped nullable so {@code ddl-auto: update} can add the column to a table that already has
     * rows; {@link com.geneinvoice.common.RowVersionUpgrade} fills those in at startup.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.invoiceDate == null) {
            this.invoiceDate = this.createdAt;
        }
        // No invoice reaches the database without a due date (AC-A1). The service derives it from
        // the customer's terms; this is the last resort for any other path.
        if (this.dueDate == null) {
            this.paymentTerm = PaymentTerm.SYSTEM_DEFAULT;
            this.dueDate = PaymentTerm.SYSTEM_DEFAULT.due(InvoiceDates.dayOf(this.invoiceDate));
        } else if (this.paymentTerm == null) {
            this.paymentTerm = PaymentTerm.CUSTOM;
        }
    }

    public BigDecimal getBalance() {
        return total.subtract(paidAmount);
    }

    /**
     * Overdue is read from the clock, never stored (D3): the same row becomes overdue when the day
     * after its due date starts, with nothing written to it. An invoice that owes nothing, or that
     * was cancelled, is never overdue whatever its date says.
     */
    public boolean isOverdue(LocalDate today) {
        return dueDate != null && dueDate.isBefore(today)
                && getBalance().signum() > 0 && status != InvoiceStatus.CANCELLED;
    }

    /** Whole days between the due date and today, and zero when the invoice is not overdue. */
    public int daysOverdue(LocalDate today) {
        return isOverdue(today) ? (int) ChronoUnit.DAYS.between(dueDate, today) : 0;
    }
}
