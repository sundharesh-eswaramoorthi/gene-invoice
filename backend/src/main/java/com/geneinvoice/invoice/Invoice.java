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

    @Column(name = "due_date")
    private LocalDate dueDate;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_poc_user_id")
    private User salesPoc;

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

    public boolean isOverdue(LocalDate today) {
        return dueDate != null && dueDate.isBefore(today)
                && getBalance().signum() > 0 && status != InvoiceStatus.CANCELLED;
    }

    public int daysOverdue(LocalDate today) {
        return isOverdue(today) ? (int) ChronoUnit.DAYS.between(dueDate, today) : 0;
    }
}
