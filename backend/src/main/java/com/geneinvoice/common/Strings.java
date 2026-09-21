package com.geneinvoice.common;

public final class Strings {

    private Strings() {}

    public static String blankToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String trim(String raw) {
        return raw == null ? null : raw.trim();
    }

    public static final char LIKE_ESCAPE = '\\';

    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
