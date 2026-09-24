package com.geneinvoice.region;

import com.geneinvoice.privilege.Privileges;

import java.util.Map;
import java.util.Set;

import static com.geneinvoice.region.RegionRight.APPROVE;
import static com.geneinvoice.region.RegionRight.MANAGE;
import static com.geneinvoice.region.RegionRight.VIEW;
import static java.util.Map.entry;

/**
 * The partition of every privilege into "needs a region right" and "company-wide", in the
 * TableSchemaController.VIEW_PRIVILEGE idiom. Not one of the @PreAuthorize annotations changes:
 * the global privilege still says WHAT, and this table says which region right says WHERE (B1).
 */
public final class RegionRights {

    private RegionRights() {}

    private static final Map<String, RegionRight> LEVEL = Map.ofEntries(
            entry(Privileges.CUSTOMER_VIEW, VIEW), entry(Privileges.CUSTOMER_MANAGE, MANAGE),
            entry(Privileges.INVOICE_VIEW, VIEW), entry(Privileges.INVOICE_MANAGE, MANAGE),
            entry(Privileges.PAYMENT_VIEW, VIEW), entry(Privileges.PAYMENT_MANAGE, MANAGE),
            entry(Privileges.PROMISE_VIEW, VIEW), entry(Privileges.PROMISE_MANAGE, MANAGE),
            entry(Privileges.PROMISE_OVERRIDE, MANAGE),
            entry(Privileges.DISPUTE_VIEW, VIEW), entry(Privileges.DISPUTE_CREATE, MANAGE),
            entry(Privileges.DISPUTE_MANAGE, MANAGE),
            entry(Privileges.POC_VIEW, VIEW), entry(Privileges.POC_ASSIGN, MANAGE),
            entry(Privileges.DOCUMENT_VIEW, VIEW), entry(Privileges.DOCUMENT_MANAGE, MANAGE),
            entry(Privileges.EMAIL_VIEW, VIEW), entry(Privileges.EMAIL_SEND, MANAGE),
            entry(Privileges.AUDIT_VIEW, VIEW), entry(Privileges.EXPORT_DATA, VIEW),
            // Approving is its own right and MANAGE does not imply it, so a maker is never
            // automatically the checker (B2, B1).
            entry(Privileges.APPROVAL_VIEW, VIEW), entry(Privileges.APPROVAL_APPROVE, APPROVE),
            entry(Privileges.APPROVAL_CONFIGURE, MANAGE),
            entry(Privileges.TASK_VIEW, VIEW), entry(Privileges.TASK_MANAGE, MANAGE));

    /**
     * Company-wide on purpose: the vocabulary and the people are the company's, not a branch's.
     * SCOPE_OVERRIDE is here because it turns the POC BOOK off and never widens the region set,
     * and AUTOMATION_* because authoring a rule is company-wide while a rule's reach is bounded
     * by its own regions (B1, A1 INTEGRATION).
     */
    private static final Set<String> COMPANY_WIDE = Set.of(
            Privileges.USER_VIEW, Privileges.USER_MANAGE,
            Privileges.ROLE_VIEW, Privileges.ROLE_MANAGE,
            Privileges.PRODUCT_VIEW, Privileges.PRODUCT_MANAGE,
            Privileges.NOTIFICATION_VIEW,
            Privileges.SCOPE_OVERRIDE,
            Privileges.POC_ASSIGNABLE_SALES, Privileges.POC_ASSIGNABLE_SUCCESS,
            Privileges.POC_ASSIGNABLE_COLLECTION,
            Privileges.REGION_VIEW, Privileges.REGION_MANAGE,
            Privileges.APPROVAL_APPROVE_ANY,
            Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE, Privileges.AUTOMATION_RUN);

    /**
     * The region right a privilege needs, or null when it is company-wide. Throws for a privilege
     * in neither map, so one added next year cannot quietly default to "anywhere" (B1).
     */
    public static RegionRight needed(String privilege) {
        // Guarded rather than passed straight to the maps: an immutable Map and Set both throw on
        // a null lookup, and an unnamed privilege is unclassified, never company-wide (B1).
        if (privilege != null) {
            RegionRight r = LEVEL.get(privilege);
            if (r != null) return r;
            if (COMPANY_WIDE.contains(privilege)) return null;
        }
        throw new IllegalStateException("Privilege " + privilege
                + " has no region level; add it to RegionRights.LEVEL or COMPANY_WIDE (B1)");
    }
}
