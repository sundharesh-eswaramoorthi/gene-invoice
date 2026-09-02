package com.geneinvoice.creditnote;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Permanently retained evidence of one credit issued against one invoice.
 *
 * Amount, trimmed reason, issuer and server issue time are captured once at
 * issuance and can never change: the entity exposes no way to mutate them and
 * their columns are write-once ({@code updatable = false}). The record is never
 * deleted; the only permitted change is the one-way transition to VOIDED via
 * {@link #markVoided()}.
 */
@Entity
@Table(name = "credit_notes", indexes = {
        @Index(name = "idx_credit_note_invoice", columnList = "invoice_id")
})
@Getter
@NoArgsConstructor
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", updatable = false)
    private Invoice invoice;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "issued_by_user_id", nullable = false, updatable = false)
    private Long issuedByUserId;

    @Column(name = "issued_by_username", nullable = false, length = 80, updatable = false)
    private String issuedByUsername;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditNoteStatus status;

    /**
     * Creates a new ACTIVE credit note. The amount must already be validated
     * (positive, scale 2, within the creditable outstanding) and the reason
     * already trimmed by the issuing command.
     */
    public static CreditNote issue(Invoice invoice, BigDecimal amount, String reason, User issuer) {
        CreditNote note = new CreditNote();
        note.invoice = invoice;
        note.amount = amount;
        note.reason = reason;
        note.issuedByUserId = issuer.getId();
        note.issuedByUsername = issuer.getUsername();
        note.issuedAt = Instant.now();
        note.status = CreditNoteStatus.ACTIVE;
        return note;
    }

    /** One-way transition to VOIDED. A second void is refused, never a no-op. */
    public void markVoided() {
        if (this.status != CreditNoteStatus.ACTIVE) {
            throw new BadRequestException("Credit note has already been voided");
        }
        this.status = CreditNoteStatus.VOIDED;
    }

    public boolean isActive() {
        return this.status == CreditNoteStatus.ACTIVE;
    }
}
