package com.geneinvoice.invoice;

import jakarta.persistence.*;
import lombok.*;

/** The single row that invoice numbers are drawn from; see {@link InvoiceNumbers}. */
@Entity
@Table(name = "invoice_number_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceNumberSequence {

    @Id
    private Long id;

    /** The UTC day, yyyyMMdd, that {@link #lastNumber} counts within. */
    @Column(name = "issued_day", length = 8)
    private String issuedDay;

    @Column(name = "last_number", nullable = false)
    private long lastNumber;
}
