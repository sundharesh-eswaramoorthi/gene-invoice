package com.geneinvoice.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {}

    public static final String CENTS_MESSAGE = "must have at most 2 decimal places";

    public static void requireCents(BigDecimal amount, String field) {
        if (amount != null && amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(field + " " + CENTS_MESSAGE);
        }
    }

    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        String plain = value.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        StringBuilder grouped = new StringBuilder();
        if (whole.length() <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, whole.length() - 3);
            int first = head.length() % 2 == 0 ? 2 : 1;
            grouped.append(head, 0, first);
            for (int i = first; i < head.length(); i += 2) {
                grouped.append(',').append(head, i, i + 2);
            }
            grouped.append(',').append(whole.substring(whole.length() - 3));
        }
        return (value.signum() < 0 ? "-" : "") + "\u20b9" + grouped + plain.substring(dot);
    }
}
