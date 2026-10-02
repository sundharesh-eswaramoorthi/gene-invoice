package com.geneinvoice.privilege;

import com.geneinvoice.region.AccessLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Privileges {
    private Privileges() {}

    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_MANAGE = "USER_MANAGE";

    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_MANAGE = "ROLE_MANAGE";

    public static final String CUSTOMER_VIEW = "CUSTOMER_VIEW";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";

    public static final String PRODUCT_VIEW = "PRODUCT_VIEW";
    public static final String PRODUCT_MANAGE = "PRODUCT_MANAGE";

    public static final String INVOICE_VIEW = "INVOICE_VIEW";
    public static final String INVOICE_MANAGE = "INVOICE_MANAGE";

    public static final String PAYMENT_VIEW = "PAYMENT_VIEW";
    public static final String PAYMENT_MANAGE = "PAYMENT_MANAGE";

    public static final String DISPUTE_CREATE = "DISPUTE_CREATE";
    public static final String DISPUTE_VIEW = "DISPUTE_VIEW";
    public static final String DISPUTE_MANAGE = "DISPUTE_MANAGE";

    public static final String NOTIFICATION_VIEW = "NOTIFICATION_VIEW";

    public static final String AUDIT_VIEW = "AUDIT_VIEW";

    public static final String POC_VIEW = "POC_VIEW";
    public static final String POC_ASSIGN = "POC_ASSIGN";
    public static final String POC_ASSIGNABLE_SALES = "POC_ASSIGNABLE_SALES";
    public static final String POC_ASSIGNABLE_SUCCESS = "POC_ASSIGNABLE_SUCCESS";
    public static final String POC_ASSIGNABLE_COLLECTION = "POC_ASSIGNABLE_COLLECTION";

    public static final String SCOPE_OVERRIDE = "SCOPE_OVERRIDE";

    public static final String PROMISE_VIEW = "PROMISE_VIEW";
    public static final String PROMISE_MANAGE = "PROMISE_MANAGE";
    public static final String PROMISE_OVERRIDE = "PROMISE_OVERRIDE";

    public static final String EMAIL_VIEW = "EMAIL_VIEW";
    public static final String EMAIL_SEND = "EMAIL_SEND";

    public static final String DOCUMENT_VIEW = "DOCUMENT_VIEW";
    public static final String DOCUMENT_MANAGE = "DOCUMENT_MANAGE";

    public static final String EXPORT_DATA = "EXPORT_DATA";

    public static final String REGION_VIEW = "REGION_VIEW";
    public static final String REGION_MANAGE = "REGION_MANAGE";

    public static final String TASK_VIEW = "TASK_VIEW";
    public static final String TASK_MANAGE = "TASK_MANAGE";

    /** Approve or reject someone else's held change in the region (B2). */
    public static final String CHANGE_APPROVE = "CHANGE_APPROVE";
    public static final String APPROVAL_THRESHOLD_MANAGE = "APPROVAL_THRESHOLD_MANAGE";

    /** See rules and their runs (A1–A5); which rules is {@code RuleVisibility}'s business. */
    public static final String AUTOMATION_VIEW = "AUTOMATION_VIEW";
    /** Make rules of one's own, which then run as their owner (P3). */
    public static final String AUTOMATION_MANAGE = "AUTOMATION_MANAGE";
    /** See every rule, stop any, and take one over to change it or make it act. */
    public static final String AUTOMATION_ADMIN = "AUTOMATION_ADMIN";

    public static final List<String> ALL = List.of(
            USER_VIEW, USER_MANAGE,
            ROLE_VIEW, ROLE_MANAGE,
            CUSTOMER_VIEW, CUSTOMER_MANAGE,
            PRODUCT_VIEW, PRODUCT_MANAGE,
            INVOICE_VIEW, INVOICE_MANAGE,
            PAYMENT_VIEW, PAYMENT_MANAGE,
            DISPUTE_CREATE, DISPUTE_VIEW, DISPUTE_MANAGE,
            NOTIFICATION_VIEW,
            AUDIT_VIEW,
            POC_VIEW, POC_ASSIGN,
            POC_ASSIGNABLE_SALES, POC_ASSIGNABLE_SUCCESS, POC_ASSIGNABLE_COLLECTION,
            SCOPE_OVERRIDE,
            PROMISE_VIEW, PROMISE_MANAGE, PROMISE_OVERRIDE,
            EMAIL_VIEW, EMAIL_SEND,
            DOCUMENT_VIEW, DOCUMENT_MANAGE,
            EXPORT_DATA,
            REGION_VIEW, REGION_MANAGE,
            TASK_VIEW, TASK_MANAGE,
            CHANGE_APPROVE, APPROVAL_THRESHOLD_MANAGE,
            AUTOMATION_VIEW, AUTOMATION_MANAGE, AUTOMATION_ADMIN
    );

    /** Privileges the role alone decides, whatever the region (§4.1). Every other one is regional. */
    public static final Set<String> GLOBAL = Set.of(
            USER_VIEW, USER_MANAGE,
            ROLE_VIEW, ROLE_MANAGE,
            PRODUCT_VIEW, PRODUCT_MANAGE,
            NOTIFICATION_VIEW,
            EXPORT_DATA,
            REGION_VIEW, REGION_MANAGE,
            AUTOMATION_VIEW, AUTOMATION_MANAGE, AUTOMATION_ADMIN
    );

    /** The least grant level a regional privilege needs in a region to be effective there. */
    public static final Map<String, AccessLevel> REGION_LEVEL = Map.ofEntries(
            Map.entry(CUSTOMER_VIEW, AccessLevel.READ),
            Map.entry(INVOICE_VIEW, AccessLevel.READ),
            Map.entry(PAYMENT_VIEW, AccessLevel.READ),
            Map.entry(PROMISE_VIEW, AccessLevel.READ),
            Map.entry(DISPUTE_VIEW, AccessLevel.READ),
            Map.entry(AUDIT_VIEW, AccessLevel.READ),
            Map.entry(POC_VIEW, AccessLevel.READ),
            Map.entry(EMAIL_VIEW, AccessLevel.READ),
            Map.entry(DOCUMENT_VIEW, AccessLevel.READ),
            Map.entry(SCOPE_OVERRIDE, AccessLevel.READ),
            Map.entry(POC_ASSIGNABLE_SALES, AccessLevel.READ),
            Map.entry(POC_ASSIGNABLE_SUCCESS, AccessLevel.READ),
            Map.entry(POC_ASSIGNABLE_COLLECTION, AccessLevel.READ),
            Map.entry(TASK_VIEW, AccessLevel.READ),
            Map.entry(CUSTOMER_MANAGE, AccessLevel.MANAGE),
            Map.entry(INVOICE_MANAGE, AccessLevel.MANAGE),
            Map.entry(PAYMENT_MANAGE, AccessLevel.MANAGE),
            Map.entry(PROMISE_MANAGE, AccessLevel.MANAGE),
            Map.entry(PROMISE_OVERRIDE, AccessLevel.MANAGE),
            Map.entry(DISPUTE_MANAGE, AccessLevel.MANAGE),
            Map.entry(DISPUTE_CREATE, AccessLevel.MANAGE),
            Map.entry(POC_ASSIGN, AccessLevel.MANAGE),
            Map.entry(EMAIL_SEND, AccessLevel.MANAGE),
            Map.entry(DOCUMENT_MANAGE, AccessLevel.MANAGE),
            Map.entry(TASK_MANAGE, AccessLevel.MANAGE),
            Map.entry(CHANGE_APPROVE, AccessLevel.APPROVE),
            Map.entry(APPROVAL_THRESHOLD_MANAGE, AccessLevel.APPROVE)
    );

    /** Null for a global privilege; a name in neither list (a custom one) needs MANAGE, failing safe. */
    public static AccessLevel requiredLevel(String privilege) {
        if (GLOBAL.contains(privilege)) return null;
        return REGION_LEVEL.getOrDefault(privilege, AccessLevel.MANAGE);
    }

    public static boolean isRegional(String privilege) {
        return !GLOBAL.contains(privilege);
    }
}
