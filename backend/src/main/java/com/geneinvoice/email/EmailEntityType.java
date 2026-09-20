package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

/**
 * The kinds of record an email can be about (E1). Notifications, audit rows and emails themselves
 * are not email targets.
 */
public enum EmailEntityType {
    CUSTOMER,
    INVOICE,
    PRODUCT,
    PAYMENT,
    PROMISE,
    DISPUTE,
    USER,
    ROLE;

    /** What one record is called in a sentence, e.g. "this invoice". */
    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The noun to open a label with, e.g. "Invoice level". */
    public String title() {
        return noun().substring(0, 1).toUpperCase(Locale.ROOT) + noun().substring(1);
    }

    /** Reads a type sent as text, in a query parameter or a bulk action's params; an unknown one is a 400. */
    public static EmailEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (EmailEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
