package com.geneinvoice.customer;

import com.geneinvoice.invoice.PaymentTerm;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal creditBalance = BigDecimal.ZERO;

    /**
     * How long this customer has to pay, taken as the default for their new invoices. Null is a
     * legitimate state meaning "use the system default", and CUSTOM is refused: a custom date
     * belongs on one invoice, not on every invoice a customer will ever get (D1).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", length = 20)
    private PaymentTerm paymentTerm;

    /**
     * Guards {@code creditBalance} against a writer that did not take the row lock the money path
     * takes ({@link CustomerRepository#findByIdForUpdate}): the update then fails loudly and the
     * caller is told to reload, instead of silently overwriting the credit another payment just
     * added (PPD-01). Mapped nullable so {@code ddl-auto: update} can add the column to a table
     * that already has rows; {@link com.geneinvoice.common.RowVersionUpgrade} fills those in.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
