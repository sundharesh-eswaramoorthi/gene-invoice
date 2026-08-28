package com.geneinvoice.creditnote;

import com.geneinvoice.invoice.Invoice;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "credit_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private Long issuedByUserId;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CreditNoteStatus status = CreditNoteStatus.ACTIVE;

    @PrePersist
    void onCreate() {
        this.issuedAt = Instant.now();
        if (this.reason != null) {
            this.reason = this.reason.trim();
        }
        if (this.status == null) {
            this.status = CreditNoteStatus.ACTIVE;
        }
    }
}
