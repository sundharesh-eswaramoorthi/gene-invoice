package com.geneinvoice.invoice;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.creditnote.CreditNote;
import com.geneinvoice.creditnote.CreditNoteStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
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

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * Permanently retained credit notes of this invoice (active and voided alike).
     * Read-only inverse relation: no cascade and no orphan removal, so voiding or
     * any invoice change can never delete issuance evidence.
     */
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY)
    @Builder.Default
    private List<CreditNote> creditNotes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.UNPAID;

    @Column(length = 500)
    private String notes;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.invoiceDate == null) {
            this.invoiceDate = this.createdAt;
        }
    }

    /** Sum of this invoice's ACTIVE (non-voided) credit notes. Voided notes contribute nothing. */
    public BigDecimal getActiveCreditedTotal() {
        return creditNotes.stream()
                .filter(cn -> cn.getStatus() == CreditNoteStatus.ACTIVE)
                .map(CreditNote::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The invoice's one derived outstanding amount: total minus successful
     * payments minus active credit notes, floored at zero. Credits reduce what
     * the invoice owes WITHOUT touching paidAmount (cash) or the customer's
     * creditBalance (wallet).
     */
    public BigDecimal getBalance() {
        BigDecimal outstanding = total.subtract(paidAmount).subtract(getActiveCreditedTotal());
        return outstanding.signum() < 0 ? BigDecimal.ZERO : outstanding;
    }
}
