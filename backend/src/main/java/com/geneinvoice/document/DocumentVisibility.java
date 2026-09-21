package com.geneinvoice.document;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

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
