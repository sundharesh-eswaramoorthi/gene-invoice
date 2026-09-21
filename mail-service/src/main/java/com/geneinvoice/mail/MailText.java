package com.geneinvoice.mail;

/** The small text rules the service shares with the backend's {@code EmailText} (E16). */
public final class MailText {

    private MailText() {}

    public static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return start(text, max - 1) + "…";
    }

    public static String start(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    public static String storable(String text) {
        if (text == null || text.chars().noneMatch(c -> c == 0 || Character.isSurrogate((char) c))) return text;
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
                .filter(cp -> cp != 0)
                .map(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE ? 0xFFFD : cp)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\r\\n|\\r|\\n", " ").trim();
    }

    public static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
