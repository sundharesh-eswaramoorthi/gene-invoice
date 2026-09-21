package com.geneinvoice.document;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

public enum DocumentEntityType {
    CUSTOMER,
    INVOICE,
    PAYMENT;

    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String link(Long id) {
        return "/" + noun() + "s/" + id;
    }

    public static DocumentEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (DocumentEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
