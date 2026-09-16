package com.geneinvoice.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /**
     * A customer's other addresses as stored: each normalised, blanks dropped, and each address
     * once however it is capitalised — including the main address, which is not repeated here.
     */
    public static List<String> normalizeOthers(List<String> raw, String main) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        if (main != null) seen.add(main.toLowerCase(Locale.ROOT));
        for (String r : raw) {
            String e = normalize(r);
            if (e != null && seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        if (out.size() > FieldLimits.CUSTOMER_EXTRA_EMAILS) {
            throw new BadRequestException("A customer can have at most "
                    + FieldLimits.CUSTOMER_EXTRA_EMAILS + " other email addresses");
        }
        return out;
    }
}
