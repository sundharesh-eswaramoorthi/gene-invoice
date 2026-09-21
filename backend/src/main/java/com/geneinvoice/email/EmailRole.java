package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.poc.PocType;

import java.util.Arrays;

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

    public String label() {
        return pocType.label();
    }

    public static EmailRole parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (EmailRole r : values()) {
            if (r.name().equalsIgnoreCase(wanted)) return r;
        }
        throw new BadRequestException("role must be one of " + Arrays.toString(values()));
    }
}
