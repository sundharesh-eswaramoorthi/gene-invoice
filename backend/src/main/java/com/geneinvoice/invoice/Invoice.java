package com.geneinvoice.invoice;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice implements InvoiceView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 40)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // The SAME column mapped a second time, read-only, so a book predicate and a ColumnDef can
    // name the id without walking the association. No DDL change — Hibernate writes the column
    // once, through the @ManyToOne above, and this property only ever reads it. It is what makes
    // the one lambda compile against InvoiceHistory too: a mirror maps its foreign keys as plain
    // Longs and has no Customer to walk (B3).
    @Column(name = "customer_id", insertable = false, updatable = false)
    private Long customerId;

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

    // The same read-only duplicate, for the same reason, over the POC the book is drawn on (B3).
    @Column(name = "sales_poc_user_id", insertable = false, updatable = false)
    private Long salesPocUserId;

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

    /**
     * THE FLAT COLUMN IS A LOAD-TIME MIRROR, SO THE ASSOCIATION ANSWERS THIS AND NOT THE FIELD.
     * Hibernate never refreshes an {@code insertable = false} property after an insert or an
     * update, so on the entity instance a create path just saved — the one every POST answers
     * with — {@code customerId} is still null while the association is right. Reading the
     * association also means a re-pointed row cannot answer with the account it used to be on.
     * {@code .getId()} on the lazy proxy initialises nothing, so this costs no query (B3).
     *
     * <p>The mapped field above is untouched and is still what a criteria path names: the point of
     * it was always {@code root.get("customerId")}, never a getter (B3).
     */
    @Override
    public Long getCustomerId() {
        return customer == null ? null : customer.getId();
    }

    /**
     * The same rule over the POC the book is drawn on, and here the association is the ONLY honest
     * answer: taking the person off with {@code setSalesPoc(null)} leaves the duplicate column
     * holding the id they used to have until the row is read again (B3).
     */
    public Long getSalesPocUserId() {
        return salesPoc == null ? null : salesPoc.getId();
    }

    // getBalance(), isOverdue(LocalDate) and daysOverdue(LocalDate) used to live here and now live
    // as defaults on InvoiceView, so a live list and an as-of list read ONE definition of "overdue"
    // rather than each working it out and eventually disagreeing. The bodies moved verbatim (B3).

    /**
     * The account's name. It initialises the lazy proxy, which every caller of this already did:
     * the DTO factories read the name on the line after the id (B3).
     */
    @Override
    public String getCustomerName() {
        return customer == null ? null : customer.getName();
    }

    /**
     * The row says where it lives, so the client can hide an edit button on a record in a region
     * the caller only reads rather than offering it and collecting a 403. Null-tolerant on purpose:
     * an invoice is never unplaced once customers.region_id is not null, but a DTO built from a
     * half-migrated row must not throw (B1).
     *
     * <p>It reads the account's region rather than a column of its own, because customers.region_id
     * is the only region column there is (B1, B3).
     */
    @Override
    public Long getRegionId() {
        return customer == null || customer.getRegion() == null ? null : customer.getRegion().getId();
    }

    @Override
    public String getRegionName() {
        return customer == null || customer.getRegion() == null ? null : customer.getRegion().getName();
    }
}
