package com.geneinvoice.privilege;

import java.util.List;

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

    // See the region map and the grants; manage regions and who is granted which of them (B1).
    public static final String REGION_VIEW = "REGION_VIEW";
    public static final String REGION_MANAGE = "REGION_MANAGE";

    public static final String APPROVAL_VIEW = "APPROVAL_VIEW";               // see the queue and the panel (B2)
    public static final String APPROVAL_APPROVE = "APPROVAL_APPROVE";         // decide, in a region (B2)
    // Break-glass for a region with a single approver, and for bootstrap. It never waives
    // maker != approver: nobody approves their own change, however many privileges they hold (B2).
    public static final String APPROVAL_APPROVE_ANY = "APPROVAL_APPROVE_ANY";
    public static final String APPROVAL_CONFIGURE = "APPROVAL_CONFIGURE";     // propose a threshold change (B2)

    public static final String TASK_VIEW = "TASK_VIEW";                       // (A6)
    public static final String TASK_MANAGE = "TASK_MANAGE";                   // (A6)

    // Authoring is company-wide on purpose: this privilege says you may write rules, and WHERE a
    // rule may reach is bounded by automation_rule_regions, validated at save against the author's
    // MANAGE grants (A1, B1 INTEGRATION).
    public static final String AUTOMATION_VIEW = "AUTOMATION_VIEW";
    public static final String AUTOMATION_MANAGE = "AUTOMATION_MANAGE";
    public static final String AUTOMATION_RUN = "AUTOMATION_RUN";             // run a rule now, dry or applied (A5)

    // A constant missing from ALL is never created by DataSeeder, so it can never be granted and
    // every @PreAuthorize naming it denies everyone.
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
            APPROVAL_VIEW, APPROVAL_APPROVE, APPROVAL_APPROVE_ANY, APPROVAL_CONFIGURE,
            TASK_VIEW, TASK_MANAGE,
            AUTOMATION_VIEW, AUTOMATION_MANAGE, AUTOMATION_RUN
    );
}
