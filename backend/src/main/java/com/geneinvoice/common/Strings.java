package com.geneinvoice.common;

/**
 * Free text as stored: trimmed, and a box the user left empty means "not given" rather than an
 * empty string. The distinction matters past the database — the tables render null as "—" and an
 * empty string as an empty cell, so an untrimmed or empty value shows up as a ragged row (CP-15).
 */
public final class Strings {

    private Strings() {}

    /** Trimmed, with blank collapsed to null. Null in, null out. */
    public static String blankToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Trimmed, keeping blank as blank — for a required field, whose own validation reports the
     * blank rather than having it silently become null here.
     */
    public static String trim(String raw) {
        return raw == null ? null : raw.trim();
    }

    /** The escape character every LIKE built from user text is run with. */
    public static final char LIKE_ESCAPE = '\\';

    /**
     * Neutralises LIKE wildcards so a user's {@code %} or {@code _} matches only itself. Every
     * search box that reaches a LIKE has to go through this: a name with an underscore in it
     * otherwise matches on any character, and a lone {@code %} matches the lot (CP-11).
     */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
