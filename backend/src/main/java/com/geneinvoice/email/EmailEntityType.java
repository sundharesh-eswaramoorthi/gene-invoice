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

    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String title() {
        return noun().substring(0, 1).toUpperCase(Locale.ROOT) + noun().substring(1);
    }

    public static EmailEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (EmailEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
