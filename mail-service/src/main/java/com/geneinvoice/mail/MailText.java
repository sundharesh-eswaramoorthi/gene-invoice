package com.geneinvoice.mail;

/** The small text rules the service shares with the backend's {@code EmailText} (E16). */
public final class MailText {

    private MailText() {}

    /** Shortened to fit its column rather than failing, with the cut marked. */
    public static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return start(text, max - 1) + "…";
    }

    /**
     * At most {@code length} UTF-16 units from the start, never ending between the two halves of a
     * character outside the BMP (an emoji): the half left over is not UTF-8, and Postgres would store it
     * as '?'. The whole character is dropped instead.
     */
    public static String start(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    /**
     * Text as Postgres can store it: without NUL, which a text column rejects, and with a lone
     * surrogate, which is not UTF-8, replaced by U+FFFD. Null stays null.
     */
    public static String storable(String text) {
        if (text == null || text.chars().noneMatch(c -> c == 0 || Character.isSurrogate((char) c))) return text;
        StringBuilder out = new StringBuilder(text.length());
        // A paired surrogate comes out as one supplementary code point; a lone one as itself.
        text.codePoints()
                .filter(cp -> cp != 0)
                .map(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE ? 0xFFFD : cp)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** A subject is a single trimmed line. */
    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\r\\n|\\r|\\n", " ").trim();
    }

    public static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
