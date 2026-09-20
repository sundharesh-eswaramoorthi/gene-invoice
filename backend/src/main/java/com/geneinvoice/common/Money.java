package com.geneinvoice.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /**
     * An amount as a money figure is written down — two decimals, and nothing at all for an
     * absent one. Shared by the exports, so a zero reads {@code 0.00} beside every other column
     * rather than as a bare {@code 0} (CP-14).
     */
    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Money the way the app shows it — {@code \u20b91,20,000.00}, grouped in lakhs and crores — which
     * java.text cannot do: DecimalFormat supports only one grouping size. It lives here rather
     * than in the email package because a notification reads the same figure an email does, and
     * used to print it bare (PPD-06).
     */
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
