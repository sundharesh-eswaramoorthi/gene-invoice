package com.geneinvoice.email;

import com.geneinvoice.common.Money;
import com.geneinvoice.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class EmailText {

    private EmailText() {}

    /** How staff appear to a customer when no role describes them (E13). */
    static final String TEAM = "Gene Invoice team";

    static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return start(text, max - 1) + "…";
    }

    static String start(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    static String storable(String text) {
        if (text == null || text.chars().noneMatch(c -> c == 0 || Character.isSurrogate((char) c))) return text;
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
                .filter(cp -> cp != 0)
                .map(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE ? 0xFFFD : cp)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** A subject is a single trimmed line (E16). */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\r\\n|\\r|\\n", " ").trim();
    }

    static String nameOf(User u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName().trim();
    }

    static String money(BigDecimal amount) {
        return Money.format(amount);
    }

    static String date(Instant instant, ZoneId zone) {
        return instant == null ? "" : LocalDate.ofInstant(instant, zone).toString();
    }

    static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    static String humanize(Enum<?> value) {
        String words = value.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
