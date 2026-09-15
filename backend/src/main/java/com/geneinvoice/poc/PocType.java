package com.geneinvoice.poc;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.privilege.Privileges;

import java.util.Arrays;

/** The three kinds of point-of-contact this app tracks. */
public enum PocType {
    SALES(Privileges.POC_ASSIGNABLE_SALES, "Sales POC"),
    SUCCESS(Privileges.POC_ASSIGNABLE_SUCCESS, "Customer Success POC"),
    COLLECTION(Privileges.POC_ASSIGNABLE_COLLECTION, "Collection POC");

    private final String assignabilityPrivilege;
    private final String label;

    PocType(String assignabilityPrivilege, String label) {
        this.assignabilityPrivilege = assignabilityPrivilege;
        this.label = label;
    }

    /** A user may be assigned as this POC when their role carries this privilege. */
    public String assignabilityPrivilege() {
        return assignabilityPrivilege;
    }

    public String label() {
        return label;
    }

    /** Reads a POC type sent as text, e.g. in a bulk action's params; an unknown one is a 400. */
    public static PocType parse(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        for (PocType t : values()) {
            if (t.name().equalsIgnoreCase(wanted)) return t;
        }
        throw new BadRequestException("pocType must be one of " + Arrays.toString(values()));
    }
}
