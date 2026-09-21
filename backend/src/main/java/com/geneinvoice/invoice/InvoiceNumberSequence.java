package com.geneinvoice.invoice;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "invoice_number_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceNumberSequence {

    @Id
    private Long id;

    @Column(name = "issued_day", length = 8)
    private String issuedDay;

    @Column(name = "last_number", nullable = false)
    private long lastNumber;
}
