package com.geneinvoice.common;

/**
 * Email addresses as stored: trimmed, and a blank one means no email. Blank must be null rather
 * than '' because user emails are unique, and a second '' would collide with the first.
 */
public final class Emails {

    private Emails() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    /** A conservative single-address check, applied to a value already normalised. */
    public static boolean isValid(String address) {
        return address != null && address.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
}
