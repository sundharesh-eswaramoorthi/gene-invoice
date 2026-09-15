package com.geneinvoice.poc;

import com.geneinvoice.privilege.Privileges;

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
}
