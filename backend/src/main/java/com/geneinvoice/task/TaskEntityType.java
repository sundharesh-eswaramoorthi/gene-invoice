package com.geneinvoice.task;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

/**
 * What a task can be about. A NEW enum and deliberately not EmailEntityType: adding a ninth
 * constant to that one NPEs EmailTargets.ROLES_OFFERED at class initialisation, and tasks are not
 * emailable in Part A anyway (A6).
 *
 * <p>The same three kinds DocumentEntityType offers, for the same reason — everything a person
 * chases in this product hangs off an account, an invoice or a payment (A6).
 */
public enum TaskEntityType {
    CUSTOMER,
    INVOICE,
    PAYMENT;

    public String noun() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String title() {
        return noun().substring(0, 1).toUpperCase(Locale.ROOT) + noun().substring(1);
    }

    public String link(Long id) {
        return "/" + noun() + "s/" + id;
    }

    public static TaskEntityType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (TaskEntityType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("entityType must be one of " + Arrays.toString(values()));
    }
}
