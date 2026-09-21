package com.geneinvoice.email;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's key for one recipient's copy at the mail service, {@code gi-{emailId}-{recipientId}}
 * (M11). The service keeps it, so handing a copy over twice never sends it twice.
 */
public record CopyRef(long emailId, long recipientId) {

    private static final Pattern EXTERNAL_ID = Pattern.compile("gi-(\\d{1,18})-(\\d{1,18})");

    public static String externalId(long emailId, long recipientId) {
        return "gi-" + emailId + "-" + recipientId;
    }

    public String externalId() {
        return externalId(emailId, recipientId);
    }

    public static Optional<CopyRef> parse(String externalId) {
        if (externalId == null) return Optional.empty();
        Matcher m = EXTERNAL_ID.matcher(externalId);
        if (!m.matches()) return Optional.empty();
        return Optional.of(new CopyRef(Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
    }
}
