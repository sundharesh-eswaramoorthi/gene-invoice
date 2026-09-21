package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;

public enum RoleLevel {
    CUSTOMER,
    RECORD;

    public static RoleLevel parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (RoleLevel l : values()) {
            if (l.name().equalsIgnoreCase(wanted)) return l;
        }
        throw new BadRequestException("level must be CUSTOMER or RECORD");
    }
}
