package com.geneinvoice.email;

import com.geneinvoice.common.Money;
import com.geneinvoice.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The small text rules sending, receiving and suggestions share. */
final class EmailText {

    private EmailText() {}

    /** How staff appear to a customer when no role describes them (E13). */
    static final String TEAM = "Gene Invoice team";

    /** Shortened to fit its column rather than failing, with the cut marked. */
    static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return start(text, max - 1) + "…";
    }

    /**
     * At most {@code length} UTF-16 units from the start, never ending between the two halves of a
     * character outside the BMP (an emoji): the half left over is not UTF-8, and Postgres would store it
     * as '?'. The whole character is dropped instead.
     */
    static String start(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    /**
     * Text as Postgres can store it: without NUL, which a text column rejects and received mail can
     * carry (an {@code &#0;}, a mislabelled charset), and with a lone surrogate, which is not UTF-8,
     * replaced by U+FFFD. Null stays null.
     */
    static String storable(String text) {
        if (text == null || text.chars().noneMatch(c -> c == 0 || Character.isSurrogate((char) c))) return text;
        StringBuilder out = new StringBuilder(text.length());
        // A paired surrogate comes out as one supplementary code point; a lone one as itself.
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

    /** Money the way the app shows it; the formatting itself is shared with notifications. */
    static String money(BigDecimal amount) {
        return Money.format(amount);
    }

    /**
     * The app's date format, yyyy-MM-dd, on the day it was where the reader is: the app shows a
     * timestamp's local day, so a record made at 05:20 in India is dated that day, not the UTC day before.
     */
    static String date(Instant instant, ZoneId zone) {
        return instant == null ? "" : LocalDate.ofInstant(instant, zone).toString();
    }

    static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /** SCREAMING_SNAKE to "Screaming snake", for a status in running text. */
    static String humanize(Enum<?> value) {
        String words = value.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
