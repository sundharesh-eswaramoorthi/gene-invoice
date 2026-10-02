package com.geneinvoice.history.versions;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One invoice line inside {@code invoice_versions.items_json}: the item's own columns only. The product's name is
 * another record's state (its own versions), so a product rename never makes an invoice look changed. Amounts
 * are written as strings at scale 2, so equal text means equal state.
 */
@JsonPropertyOrder({"id", "productId", "quantity", "unitPrice", "lineTotal"})
public record ItemSnapshot(Long id,
                           Long productId,
                           Integer quantity,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lineTotal) {

    public ItemSnapshot {
        unitPrice = scaled(unitPrice);
        lineTotal = scaled(lineTotal);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
