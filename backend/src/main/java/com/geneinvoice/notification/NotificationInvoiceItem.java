package com.geneinvoice.notification;

import jakarta.persistence.*;
import lombok.*;

/**
 * Structured snapshot of one invoice attached to a notification. Used by strategy
 * notifications so the per-customer invoice list is not squeezed into the bounded
 * {@code message} column; existing dispute notifications simply have no items.
 */
@Entity
@Table(name = "notification_invoice_items", indexes = {
        @Index(name = "idx_notif_item_notif", columnList = "notification_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id")
    private Notification notification;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_number", nullable = false, length = 40)
    private String invoiceNumber;
}
