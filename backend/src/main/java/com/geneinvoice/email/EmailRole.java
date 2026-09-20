package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.poc.PocType;

import java.util.Arrays;

/**
 * A point of contact relative to the record an email is about (E2). The person behind it is looked
 * up when the email is sent, never stored against the role itself; which POC is meant — the
 * customer's book or the record's own field — is the {@link RoleLevel} beside it (L1).
 */
public enum EmailRole {
    SALES_POC(PocType.SALES),
    CUSTOMER_SUCCESS_POC(PocType.SUCCESS),
    COLLECTION_POC(PocType.COLLECTION);

    private final PocType pocType;

    EmailRole(PocType pocType) {
        this.pocType = pocType;
    }

    public PocType pocType() {
        return pocType;
    }

    /** "Collection POC" — the POC kind's own label, so the two never read differently. */
    public String label() {
        return pocType.label();
    }

    /** Reads a role sent as text; an unknown one is a 400. */
    public static EmailRole parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (EmailRole r : values()) {
            if (r.name().equalsIgnoreCase(wanted)) return r;
        }
        throw new BadRequestException("role must be one of " + Arrays.toString(values()));
    }
}
