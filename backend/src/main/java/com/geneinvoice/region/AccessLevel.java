package com.geneinvoice.region;

import java.util.EnumSet;
import java.util.Set;

/** How much a grant allows in its region. Ordered: each level includes the ones before it. */
public enum AccessLevel {
    READ, MANAGE, APPROVE;

    public boolean permits(AccessLevel required) {
        return required == null || compareTo(required) >= 0;
    }

    /** This level and every higher one: the levels a grant may have for {@code min} to be met. */
    public static Set<AccessLevel> atLeast(AccessLevel min) {
        return EnumSet.range(min, APPROVE);
    }
}
