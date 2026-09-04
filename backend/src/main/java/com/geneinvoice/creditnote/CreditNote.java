package com.geneinvoice.creditnote;

import com.geneinvoice.invoice.Invoice;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable, append-preserving credit note against one invoice. Amount, reason, issuer and
 * issuance time are fixed at issuance and never edited; a note is never deleted. The only
 * transition an issued note has is a single active-to-voided change via {@link #markVoided()}.
 */
@Entity
@Table(name = "credit_notes", indexes = {
        @Index(name = "idx_credit_note_invoice", columnList = "invoice_id")
})
@Getter
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

    @Column(name = "issued_by_user_id", nullable = false, updatable = false)
    private Long issuedByUserId;

    @Column(name = "issued_by_name", length = 120, updatable = false)
    private String issuedByName;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean voided = false;

    /** The single allowed transition: active to voided. Every issued fact stays immutable. */
    public void markVoided() {
        this.voided = true;
    }
}
