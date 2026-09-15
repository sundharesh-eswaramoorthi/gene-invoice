package com.geneinvoice.common;

import java.math.BigDecimal;

/** Money is stored with two decimals, so an amount that needs more is refused, never rounded. */
public final class Money {

    private Money() {}

    /** Shared with the request DTOs' {@code @Digits}, so every path reports it the same way. */
    public static final String CENTS_MESSAGE = "must have at most 2 decimal places";

    public static void requireCents(BigDecimal amount, String field) {
        if (amount != null && amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(field + " " + CENTS_MESSAGE);
        }
    }
}
