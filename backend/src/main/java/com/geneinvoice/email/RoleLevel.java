package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;

/**
 * Which point of contact a role token means (L1): the customer's POC book, which several people
 * can sit in, or the one person the record itself stores. Both are offered wherever they exist, so
 * the level, not the role alone, is what a sender picks.
 */
public enum RoleLevel {
    /** The customer's POC book: every active holder of the seat. */
    CUSTOMER,
    /** The record's own POC field: the one person it names. */
    RECORD;

    /** Reads a level sent as text; anything else is a 400. */
    public static RoleLevel parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (RoleLevel l : values()) {
            if (l.name().equalsIgnoreCase(wanted)) return l;
        }
        throw new BadRequestException("level must be CUSTOMER or RECORD");
    }
}
