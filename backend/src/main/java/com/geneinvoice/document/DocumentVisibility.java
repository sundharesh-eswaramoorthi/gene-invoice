package com.geneinvoice.document;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * Who a document is for (D7). {@code INTERNAL} is the default on upload, so a document reaches a
 * self-service customer only by an explicit act — except a customer's own upload, which would
 * otherwise be invisible to the person who made it (§4.5).
 */
public enum DocumentVisibility {
    INTERNAL,
    SHARED;

    public static DocumentVisibility parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (DocumentVisibility v : values()) {
            if (v.name().equalsIgnoreCase(wanted)) return v;
        }
        throw new BadRequestException("visibility must be one of " + Arrays.toString(values()));
    }
}
