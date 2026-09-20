package com.geneinvoice.document;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of record a document can hang off (D5), following the shape {@code Email} already uses
 * for the same job. The enum is deliberately open: the next kind is a value here plus its two
 * privileges in {@link DocumentTargets}, and nothing else.
 */
public enum DocumentEntityType {
    CUSTOMER,
    INVOICE,
    PAYMENT;

    /** What one record is called in a sentence, e.g. "this invoice". */
    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Where the record opens in the app, for the link a document carries to its record. */
    public String link(Long id) {
        return "/" + noun() + "s/" + id;
    }

    /** Reads a type sent as text, in a query parameter or a multipart field; an unknown one is a 400. */
    public static DocumentEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (DocumentEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
