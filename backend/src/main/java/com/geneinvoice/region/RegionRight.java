package com.geneinvoice.region;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;

/**
 * What one user may do in one region. The ladder is deliberately NOT a total order: MANAGE does
 * not cover APPROVE, because four-eyes needs a checker who is not the maker, and APPROVE does not
 * cover MANAGE, because signing a change off is not the same as making one. Both cover VIEW, so
 * anyone who must both work and approve in a region holds two grant rows (B1, B2).
 *
 * <p>Adding a fourth constant means widening the {@code user_region_grants.right_level} check
 * constraint on an existing database: RegionSchemaUpgrade calls SchemaSupport.widen for that
 * column pre-emptively, so the wiring is already there (B1).
 */
public enum RegionRight {
    VIEW,
    MANAGE,
    APPROVE;

    /** True when holding this right is enough for something that needs {@code needed}. */
    public boolean covers(RegionRight needed) {
        // Everything covers VIEW and every right covers itself; nothing else is implied (B1).
        return needed == VIEW || this == needed;
    }

    public static RegionRight parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (RegionRight r : values()) {
            if (r.name().equalsIgnoreCase(wanted)) return r;
        }
        throw new BadRequestException("right must be one of " + Arrays.toString(values()));
    }
}
