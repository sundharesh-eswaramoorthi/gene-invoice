package com.geneinvoice.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of the additive DTO bridge (AQ6 / AC9): dispute-shaped notifications serialise exactly
 * as before with an empty item list, while strategy notifications expose the structured,
 * identifiable invoice snapshots per recipient.
 */
class NotificationDtoBridgeTest {

    private static Notification row(long id, String type, List<NotificationInvoiceItem> items) {
        Notification n = Notification.builder()
                .id(id).userId(7L).type(type).title("T").message("m").link("/x")
                .createdAt(Instant.parse("2024-06-01T05:00:00Z"))
                .build();
        if (items != null) {
            n.getInvoiceItems().addAll(items);
        }
        return n;
    }

    @Test
    void existingFiveArgumentRowsMapWithAnEmptyItemList() {
        Notification dispute = row(1L, "DISPUTE_OPENED", null);

        NotificationController.NotificationDto dto = NotificationController.NotificationDto.from(dispute);

        assertEquals("DISPUTE_OPENED", dto.type());
        assertTrue(dto.invoiceItems().isEmpty(),
                "dispute notifications carry no strategy invoice items");
    }

    @Test
    void strategyRowExposesEveryFrozenInvoiceSnapshot() {
        Notification n = row(2L, "STRATEGY_MATCH", List.of(
                NotificationInvoiceItem.builder().invoiceId(1001L).invoiceNumber("INV-1001").build(),
                NotificationInvoiceItem.builder().invoiceId(1003L).invoiceNumber("INV-1003").build()));

        NotificationController.NotificationDto dto = NotificationController.NotificationDto.from(n);

        assertEquals("STRATEGY_MATCH", dto.type());
        assertEquals(2, dto.invoiceItems().size());
        assertEquals(1001L, dto.invoiceItems().get(0).invoiceId());
        assertEquals("INV-1001", dto.invoiceItems().get(0).invoiceNumber());
        assertEquals("INV-1003", dto.invoiceItems().get(1).invoiceNumber());
    }
}
